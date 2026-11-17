package com.techverito.banking.event.outbox;

import com.techverito.banking.entity.OutboxEvent;
import com.techverito.banking.repository.OutboxEventRepository;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnsupportedVersionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Drains the durable outbox to Kafka: it sends the stored JSON of a row with the row's topic and
 * ordering key, waits for the broker to acknowledge it, and only then deletes the row.
 *
 * <p>Only the <em>oldest</em> pending row of each ordering key is sent in a pass, so a later change
 * of one account can never overtake an earlier one that is still waiting for a retry. A transient
 * failure (broker down, timeout, retriable broker error) is retried forever with exponential
 * backoff, because the row is only lost when someone deletes it; a failure that can never succeed
 * (an invalid topic, a record the producer cannot serialise, an oversized record) marks the row
 * {@code FAILED}, keeping the event id and the reason in the database for investigation.</p>
 *
 * <p>A failure of one row never stops the others: each row is handled on its own, so one stuck
 * subject only blocks itself. The stored payload is sent verbatim, so every attempt at a row carries
 * the same {@code eventId} and a consumer deduplicating on it sees one logical event.</p>
 */
@Component
@EnableScheduling
@EnableConfigurationProperties(OutboxRelayProperties.class)
@ConditionalOnProperty(prefix = "banking.events", name = "bus", havingValue = "kafka", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** The largest backoff shift used, so the delay computation can never overflow. */
    private static final int MAX_BACKOFF_SHIFT = 30;

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxRelayProperties properties;
    private final Clock clock;

    public OutboxRelay(OutboxEventRepository outboxEventRepository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       OutboxRelayProperties properties,
                       Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * One relay pass: read the due PENDING rows ordered by ordering key and sequence number, keep the
     * oldest row of each key, and try to publish it. Called by the scheduler every
     * {@code banking.events.outbox.poll-interval-ms}, and directly by a test that wants one pass.
     */
    @Scheduled(fixedDelayString = "${banking.events.outbox.poll-interval-ms:5000}")
    public void relayPending() {
        List<OutboxEvent> due;
        try {
            due = outboxEventRepository.findPendingForRelay(Instant.now(clock));
        } catch (RuntimeException ex) {
            log.error("Could not read the pending outbox events. They stay in the outbox and are retried "
                    + "on the next pass.", ex);
            return;
        }

        Map<String, OutboxEvent> oldestPerKey = new LinkedHashMap<>();
        for (OutboxEvent row : due) {
            oldestPerKey.putIfAbsent(row.getOrderingKey(), row);
        }

        for (OutboxEvent row : oldestPerKey.values()) {
            try {
                publish(row);
            } catch (RuntimeException ex) {
                log.error("Could not process the outbox event: eventId={}, type={}, topic={}, orderingKey={}",
                        row.getEventId(), row.getEventType(), row.getTopic(), row.getOrderingKey(), ex);
            }
        }
    }

    private void publish(OutboxEvent row) {
        try {
            kafkaTemplate.send(row.getTopic(), row.getOrderingKey(), row.getPayload())
                    .get(properties.sendTimeoutSeconds(), TimeUnit.SECONDS);
            outboxEventRepository.deleteById(row.getId());
            log.info("Published the business event: eventId={}, type={}, topic={}, orderingKey={}",
                    row.getEventId(), row.getEventType(), row.getTopic(), row.getOrderingKey());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            recordTransientFailure(row, ex);
        } catch (ExecutionException ex) {
            handleFailure(row, (ex.getCause() != null) ? ex.getCause() : ex);
        } catch (TimeoutException ex) {
            handleFailure(row, ex);
        } catch (RuntimeException ex) {
            handleFailure(row, ex);
        }
    }

    private void handleFailure(OutboxEvent row, Throwable failure) {
        if (isRetryable(failure)) {
            recordTransientFailure(row, failure);
            return;
        }
        row.markUnpublishable(reasonOf(failure));
        outboxEventRepository.save(row);
        log.error("The business event can never be published and is kept as FAILED: "
                        + "eventId={}, type={}, topic={}, orderingKey={}, attemptCount={}, reason={}",
                row.getEventId(), row.getEventType(), row.getTopic(), row.getOrderingKey(),
                row.getAttemptCount(), reasonOf(failure));
    }

    private void recordTransientFailure(OutboxEvent row, Throwable failure) {
        Instant nextAttemptAt = Instant.now(clock).plus(backoff(row.getAttemptCount() + 1));
        row.recordFailedAttempt(reasonOf(failure), nextAttemptAt);
        outboxEventRepository.save(row);
        log.warn("Could not publish the business event; it stays PENDING and is retried at {}: "
                        + "eventId={}, type={}, topic={}, orderingKey={}, attemptCount={}, reason={}",
                nextAttemptAt, row.getEventId(), row.getEventType(), row.getTopic(), row.getOrderingKey(),
                row.getAttemptCount(), reasonOf(failure));
    }

    /**
     * The exponential backoff of one attempt: {@code baseDelay * 2^attemptCount}, capped at
     * {@code maxDelayMs}. Computed by repeated doubling so a long outage can never overflow.
     */
    private Duration backoff(int attemptCount) {
        long maximum = Math.max(properties.maxDelayMs(), 0L);
        long delay = Math.max(properties.baseDelayMs(), 0L);
        int doublings = Math.min(Math.max(attemptCount, 0), MAX_BACKOFF_SHIFT);
        for (int i = 0; i < doublings && delay < maximum; i++) {
            delay *= 2;
        }
        if (delay < 0 || delay > maximum) {
            delay = maximum;
        }
        return Duration.ofMillis(delay);
    }

    /**
     * Whether another attempt can succeed. A timeout or a retriable broker error is waited out; a
     * record the producer can never accept, an invalid topic, an unsupported version or a missing
     * authorisation is not retried, so the row is kept as the investigation record instead.
     */
    private boolean isRetryable(Throwable failure) {
        if (failure instanceof TimeoutException || failure instanceof RetriableException) {
            return true;
        }
        return !(failure instanceof RecordTooLargeException
                || failure instanceof SerializationException
                || failure instanceof InvalidTopicException
                || failure instanceof UnsupportedVersionException
                || failure instanceof TopicAuthorizationException);
    }

    /** The reason recorded on the row: the failure class plus its message, never the payload. */
    private String reasonOf(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + ((message == null) ? "" : ": " + message);
    }
}
