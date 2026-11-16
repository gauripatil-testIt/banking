package com.techverito.banking.event.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.config.BackgroundTaskProperties;
import com.techverito.banking.entity.BackgroundTaskOutboxEntry;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.repository.BackgroundTaskOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Drains the background-task outbox to RabbitMQ.
 *
 * <p>The business transaction has already committed the row, so nothing here can make a
 * business action fail: the drain runs on its own schedule, publishes the stored message
 * through {@link BackgroundTaskPublisher} – which returns only once the broker confirmed
 * receipt – and marks the row {@code PUBLISHED} only then. A publish that throws leaves
 * the row {@code PENDING}, increments its attempt count and moves its next attempt
 * forward, so a transient failure is retried automatically and no task is lost while the
 * broker is down.</p>
 *
 * <p>Rows are taken in ascending id order, so a task recorded earlier is submitted before
 * one recorded later. There is no per-key blocking rule: each task type has its own queue,
 * so one slow task cannot hold up another and the rows of different task types are drained
 * independently of each other.</p>
 *
 * <p>A row whose attempts are exhausted is kept as {@code FAILED} with the reason, never
 * deleted, and one structured ERROR line reports the task id, its type and the reason, so
 * an unsendable task can still be investigated.</p>
 *
 * <p>No broker type appears here: the transport is whatever {@link BackgroundTaskPublisher}
 * was configured with.</p>
 */
@Component
public class BackgroundTaskOutboxPublisher {

    /** How much of a failure reason is kept, matching the {@code last_error} column. */
    private static final int MAX_ERROR_LENGTH = 1000;

    private static final Logger log = LoggerFactory.getLogger(BackgroundTaskOutboxPublisher.class);

    private final BackgroundTaskOutboxRepository outboxRepository;
    private final BackgroundTaskPublisher taskPublisher;
    private final ObjectMapper objectMapper;
    private final BackgroundTaskProperties properties;
    private final Clock clock;

    public BackgroundTaskOutboxPublisher(BackgroundTaskOutboxRepository outboxRepository,
                                         BackgroundTaskPublisher taskPublisher,
                                         ObjectMapper objectMapper,
                                         BackgroundTaskProperties properties,
                                         Clock clock) {
        this.outboxRepository = outboxRepository;
        this.taskPublisher = taskPublisher;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Publishes the rows that are due now, oldest first. It is run by
     * {@link com.techverito.banking.config.BackgroundTaskPublishingConfig} with the delay
     * bound from {@code banking.tasks.drain-interval}.
     */
    public void drain() {
        List<BackgroundTaskOutboxEntry> due = outboxRepository
                .findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(BackgroundTaskStatus.PENDING,
                        clock.instant(), PageRequest.of(0, properties.batchSize()));
        for (BackgroundTaskOutboxEntry entry : due) {
            publishOne(entry);
        }
    }

    /**
     * Publishes one stored task and records the outcome on its row: {@code PUBLISHED} only
     * after the broker confirmed it, otherwise the row stays unresolved with the reason
     * kept for investigation.
     */
    private void publishOne(BackgroundTaskOutboxEntry entry) {
        try {
            taskPublisher.publish(deserialise(entry));
        } catch (RuntimeException failure) {
            recordFailure(entry, failure);
            return;
        }
        entry.setStatus(BackgroundTaskStatus.PUBLISHED);
        entry.setPublishedAt(clock.instant());
        entry.setLastError(null);
        outboxRepository.save(entry);
        log.debug("Published a background task: taskId={}, type={}, recordType={}, recordId={}, attempts={}",
                entry.getTaskId(), entry.getTaskType(), entry.getRecordType(), entry.getRecordId(),
                entry.getAttempts());
    }

    /**
     * Keeps the failed row, counts the attempt and either schedules the retry or, once the
     * attempts are exhausted, keeps it as {@code FAILED} with the reason and reports it.
     */
    private void recordFailure(BackgroundTaskOutboxEntry entry, RuntimeException failure) {
        String reason = reason(failure);
        Instant now = clock.instant();
        int attempts = entry.getAttempts() + 1;
        entry.setAttempts(attempts);
        entry.setLastError(reason);
        if (attempts >= properties.maxAttempts()) {
            entry.setStatus(BackgroundTaskStatus.FAILED);
            outboxRepository.save(entry);
            log.error("A background task could not be published and is kept as FAILED: taskId={}, type={}, "
                            + "recordType={}, recordId={}, attempts={}, reason={}",
                    entry.getTaskId(), entry.getTaskType(), entry.getRecordType(), entry.getRecordId(),
                    attempts, reason);
            return;
        }
        entry.setNextAttemptAt(now.plus(properties.retryDelay()));
        outboxRepository.save(entry);
        log.warn("A background task could not be published and will be retried: taskId={}, type={}, "
                        + "recordType={}, recordId={}, attempts={}, reason={}",
                entry.getTaskId(), entry.getTaskType(), entry.getRecordType(), entry.getRecordId(),
                attempts, reason);
    }

    private BackgroundTaskMessage deserialise(BackgroundTaskOutboxEntry entry) {
        try {
            return objectMapper.readValue(entry.getPayload(), BackgroundTaskMessage.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The stored background task " + entry.getTaskId() + " ("
                    + entry.getTaskType() + ") could not be read back for publishing.", ex);
        }
    }

    private String reason(Throwable failure) {
        String message = failure.getMessage();
        String reason = (message != null && !message.isBlank()) ? message : failure.getClass().getSimpleName();
        return (reason.length() <= MAX_ERROR_LENGTH) ? reason : reason.substring(0, MAX_ERROR_LENGTH);
    }
}
