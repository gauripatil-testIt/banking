package com.techverito.banking.service;

import com.techverito.banking.config.TaskQueueProperties;
import com.techverito.banking.entity.BackgroundTask;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.entity.BackgroundTaskType;
import com.techverito.banking.event.TaskQueuePublisher;
import com.techverito.banking.exception.TaskPublishException;
import com.techverito.banking.repository.BackgroundTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The scheduled half of the outbox: it publishes the {@link BackgroundTaskStatus#PENDING}
 * rows written by {@link TaskSubmissionService} to RabbitMQ and marks a row
 * {@link BackgroundTaskStatus#SUBMITTED} only once the broker confirmed its message.
 *
 * <p>Publishing after commit is structural rather than a call: the relay reads the outbox
 * in its own new transaction, so a row written by a business transaction that has not
 * committed yet is invisible to it and can never be published early. Each run is its own
 * transaction as well, so a broker failure never rolls back the business data that is
 * already committed - the row simply stays PENDING.</p>
 *
 * <p>A task that could not be published is retried later with an exponential backoff
 * ({@code min(2^attempts, 60)} seconds), which is what makes the submission survive a
 * RabbitMQ restart or outage: the caller's request already returned, the work stays in the
 * outbox, and the next successful run hands it to the broker exactly once. A row that was
 * confirmed is never picked up again, because only PENDING rows are queried.</p>
 *
 * <p>Log lines carry the row's technical identity - task id, task type, event id,
 * correlation id and queue - and never a customer name or an email address.</p>
 */
@Component
@ConditionalOnProperty(prefix = "banking.tasks", name = "relay-enabled", havingValue = "true", matchIfMissing = true)
public class TaskOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(TaskOutboxRelay.class);

    /** The longest pause between two attempts of the same task. */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(60);

    private final BackgroundTaskRepository taskRepository;
    private final TaskQueuePublisher publisher;
    private final TaskQueueProperties properties;
    private final Clock clock;

    public TaskOutboxRelay(BackgroundTaskRepository taskRepository,
                          TaskQueuePublisher publisher,
                          TaskQueueProperties properties,
                          Clock clock) {
        this.taskRepository = taskRepository;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Publishes the pending tasks that are due: the ones that were never attempted and the
     * ones whose backoff has elapsed. A confirmed task becomes SUBMITTED, a failed one keeps
     * its PENDING state with its retry state advanced, so the next run tries it again.
     */
    @Scheduled(fixedDelayString = "${banking.tasks.relay-interval:2s}")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void relayPending() {
        Instant now = Instant.now(clock);
        List<BackgroundTask> pending = new ArrayList<>();
        pending.addAll(taskRepository
                .findTop50ByStatusAndNextAttemptAtIsNullOrderByCreatedAtAsc(BackgroundTaskStatus.PENDING));
        pending.addAll(taskRepository
                .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(BackgroundTaskStatus.PENDING, now));

        for (BackgroundTask task : pending) {
            publish(task, now);
        }
    }

    private void publish(BackgroundTask task, Instant now) {
        BackgroundTaskType taskType = task.getTaskType();
        String routingKey = properties.routingKey(taskType);
        String queue = queueName(taskType);
        try {
            publisher.publish(routingKey, task.getMessageJson());
            task.setStatus(BackgroundTaskStatus.SUBMITTED);
            task.setPublishedAt(now);
            task.setNextAttemptAt(null);
            taskRepository.save(task);
            log.info("Published a background task: taskId={}, taskType={}, eventId={}, correlationId={}, queue={}",
                    task.getId(), taskType, task.getEventId(), task.getCorrelationId(), queue);
        } catch (TaskPublishException ex) {
            int attempts = task.getAttempts() + 1;
            Instant nextAttemptAt = now.plus(backoff(attempts));
            task.setAttempts(attempts);
            task.setNextAttemptAt(nextAttemptAt);
            taskRepository.save(task);
            log.error("Could not publish the background task, it stays queued: taskId={}, taskType={}, eventId={}, "
                            + "correlationId={}, queue={}, attempts={}, nextAttemptAt={}",
                    task.getId(), taskType, task.getEventId(), task.getCorrelationId(), queue, attempts,
                    nextAttemptAt, ex);
        }
    }

    /** {@code min(2^attempts, 60)} seconds, so a retry backs off instead of hammering the broker. */
    private Duration backoff(int attempts) {
        long seconds = 1L << Math.min(attempts, 6);
        Duration backoff = Duration.ofSeconds(seconds);
        return (backoff.compareTo(MAX_BACKOFF) > 0) ? MAX_BACKOFF : backoff;
    }

    private String queueName(BackgroundTaskType taskType) {
        return switch (taskType) {
            case WELCOME_EMAIL -> properties.welcomeEmailQueue();
            case STATEMENT_GENERATION -> properties.statementGenerationQueue();
            case DOCUMENT_EXPORT -> properties.documentExportQueue();
            case DOCUMENT_SCAN -> properties.documentScanQueue();
        };
    }
}
