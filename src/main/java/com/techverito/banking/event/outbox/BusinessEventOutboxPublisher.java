package com.techverito.banking.event.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.entity.BusinessEventOutboxEntry;
import com.techverito.banking.entity.OutboxEventStatus;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.DeadLetterPublisher;
import com.techverito.banking.repository.BusinessEventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drains the transactional outbox to the configured event transport.
 *
 * <p>The business transaction has already committed the row, so nothing here can
 * make a business action fail: the drain runs on its own schedule, republishes the
 * stored envelope through {@link BusinessEventBus} – which returns only once the
 * broker acknowledged the record – and marks the row {@code PUBLISHED} only then. A
 * publish that throws leaves the row {@code PENDING}, increments its attempt count
 * and moves its next attempt forward, so it is retried automatically once the broker
 * is back and no event is lost while it is down.</p>
 *
 * <p>Ordering is per business key: rows are taken in ascending id order, and a row
 * waits while an earlier unresolved row with the same key exists. A key that failed
 * during one drain is skipped for the rest of that batch, so an account whose
 * publish failed never holds up the other accounts behind it.</p>
 *
 * <p>A row whose attempts are exhausted is kept as {@code FAILED} with the reason, when
 * it was given up on, and is announced through {@link DeadLetterPublisher}, so an event
 * that could not be published is reported with its id and kept for investigation
 * instead of being deleted.</p>
 *
 * <p>No broker type appears here: the transport is whatever {@link BusinessEventBus}
 * was configured with, and a dead-lettered event goes to whatever
 * {@link DeadLetterPublisher} was configured with, so the same drain runs against Kafka
 * or the in-memory bus.</p>
 */
@Component
public class BusinessEventOutboxPublisher {

    /** How much of a failure reason is kept, matching the {@code last_error} column. */
    private static final int MAX_ERROR_LENGTH = 1000;

    private static final Logger log = LoggerFactory.getLogger(BusinessEventOutboxPublisher.class);

    private final BusinessEventOutboxRepository outboxRepository;
    private final BusinessEventBus businessEventBus;
    private final DeadLetterPublisher deadLetterPublisher;
    private final ObjectMapper objectMapper;
    private final OutboxProperties outboxProperties;
    private final Clock clock;

    public BusinessEventOutboxPublisher(BusinessEventOutboxRepository outboxRepository,
                                        BusinessEventBus businessEventBus,
                                        DeadLetterPublisher deadLetterPublisher,
                                        ObjectMapper objectMapper,
                                        OutboxProperties outboxProperties,
                                        Clock clock) {
        this.outboxRepository = outboxRepository;
        this.businessEventBus = businessEventBus;
        this.deadLetterPublisher = deadLetterPublisher;
        this.objectMapper = objectMapper;
        this.outboxProperties = outboxProperties;
        this.clock = clock;
    }

    /**
     * Publishes the rows that are due now, oldest first, keeping each business key in
     * order while the keys stay independent of each other. It is run by
     * {@link OutboxPublishingConfig} with the delay bound from
     * {@code banking.events.outbox.drain-interval}.
     */
    public void drain() {
        List<BusinessEventOutboxEntry> due = outboxRepository
                .findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(OutboxEventStatus.PENDING,
                        clock.instant(), PageRequest.of(0, outboxProperties.batchSize()));
        if (due.isEmpty()) {
            return;
        }

        Set<String> failedKeys = new HashSet<>();
        for (BusinessEventOutboxEntry entry : due) {
            if (failedKeys.contains(entry.getBusinessKey()) || isBlockedByEarlierRow(entry)) {
                continue;
            }
            publish(entry);
            if (entry.getStatus() != OutboxEventStatus.PUBLISHED) {
                // The rest of this account's events wait for the next drain, but the
                // events of every other account still go out in this one.
                failedKeys.add(entry.getBusinessKey());
            }
        }
    }

    /**
     * Publishes one stored event and records the outcome on its row: {@code PUBLISHED}
     * only after the transport accepted it, otherwise the row stays unresolved with the
     * reason kept for investigation.
     */
    private void publish(BusinessEventOutboxEntry entry) {
        BusinessEvent event = null;
        try {
            event = deserialise(entry);
            businessEventBus.publish(event);
        } catch (RuntimeException failure) {
            recordFailure(entry, event, failure);
            return;
        }
        entry.setStatus(OutboxEventStatus.PUBLISHED);
        entry.setPublishedAt(clock.instant());
        entry.setLastError(null);
        outboxRepository.save(entry);
        log.debug("Published a business event: eventId={}, type={}, businessKey={}, attempts={}",
                entry.getEventId(), entry.getEventType(), entry.getBusinessKey(), entry.getAttempts());
    }

    /**
     * Keeps the failed row, counts the attempt and either schedules the retry or, once
     * the attempts are exhausted, keeps it as {@code FAILED} with the reason and
     * dead-letters the event, so nothing that could not be published is dropped.
     */
    private void recordFailure(BusinessEventOutboxEntry entry, BusinessEvent event, RuntimeException failure) {
        String reason = reason(failure);
        Instant now = clock.instant();
        int attempts = entry.getAttempts() + 1;
        entry.setAttempts(attempts);
        entry.setLastError(reason);
        if (attempts >= outboxProperties.maxAttempts()) {
            entry.setStatus(OutboxEventStatus.FAILED);
            entry.setDeadLetteredAt(now);
            outboxRepository.save(entry);
            deadLetter(entry, event, reason);
            return;
        }
        entry.setNextAttemptAt(now.plus(outboxProperties.retryDelay()));
        outboxRepository.save(entry);
        log.warn("A business event could not be published and will be retried: eventId={}, type={}, "
                        + "businessKey={}, attempts={}, reason={}",
                entry.getEventId(), entry.getEventType(), entry.getBusinessKey(), attempts, reason);
    }

    /**
     * Hands the event that was given up on to the dead-letter destination with the
     * reason, so it is reported with its id and kept for investigation. The row itself
     * is already saved as {@code FAILED}, so a dead-letter destination that is itself
     * unreachable only costs the notice, never the event.
     */
    private void deadLetter(BusinessEventOutboxEntry entry, BusinessEvent event, String reason) {
        if (event == null) {
            // The stored payload could not even be read back, so there is no envelope to
            // hand over; the retained row and this report are all that is left of it.
            log.error("A stored business event could not be read back and is kept as FAILED: eventId={}, "
                            + "type={}, businessKey={}, reason={}",
                    entry.getEventId(), entry.getEventType(), entry.getBusinessKey(), reason);
            return;
        }
        try {
            deadLetterPublisher.deadLetter(event, reason);
        } catch (RuntimeException ex) {
            log.warn("The dead-letter notice for eventId={} could not be delivered: {}",
                    entry.getEventId(), reason(ex));
        }
    }

    /**
     * True when an earlier row with the same business key is still unresolved, so this
     * row has to wait for it: that is what keeps one account's events in order.
     */
    private boolean isBlockedByEarlierRow(BusinessEventOutboxEntry entry) {
        return outboxRepository.existsByIdLessThanAndBusinessKeyAndStatus(entry.getId(),
                entry.getBusinessKey(), OutboxEventStatus.PENDING);
    }

    private BusinessEvent deserialise(BusinessEventOutboxEntry entry) {
        try {
            return objectMapper.readValue(entry.getPayload(), BusinessEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The stored event " + entry.getEventId() + " ("
                    + entry.getEventType() + ") could not be read back for publishing.", ex);
        }
    }

    private String reason(Throwable failure) {
        String message = failure.getMessage();
        String reason = (message != null && !message.isBlank()) ? message : failure.getClass().getSimpleName();
        return (reason.length() <= MAX_ERROR_LENGTH) ? reason : reason.substring(0, MAX_ERROR_LENGTH);
    }
}
