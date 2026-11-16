package com.techverito.banking.event.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.entity.BackgroundTaskOutboxEntry;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.event.outbox.EventActorContext;
import com.techverito.banking.repository.BackgroundTaskOutboxRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

/**
 * Records a background task in the transactional outbox, in the same transaction as the
 * business change that asked for it. Nothing here talks to a broker: the business action
 * commits its data and its pending task together, so the action still succeeds while
 * RabbitMQ is unreachable and the task is submitted once it is back.
 *
 * <p>The row holds the serialised {@link BackgroundTaskMessage}, so the drain submits
 * exactly what the business transaction recorded, together with the task type, the record
 * it applies to and the requester. The message's {@code taskId} is derived from the task
 * type and the record rather than from a random source, so submitting the same task for
 * the same record twice is recognised here and writes nothing the second time – and the
 * unique {@code task_id} on the table is the backstop when two requests race.</p>
 */
@Component
public class BackgroundTaskOutboxRecorder {

    private final BackgroundTaskOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final EventActorContext actorContext;

    public BackgroundTaskOutboxRecorder(BackgroundTaskOutboxRepository outboxRepository,
                                        ObjectMapper objectMapper,
                                        Clock clock,
                                        EventActorContext actorContext) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.actorContext = actorContext;
    }

    /**
     * Records the task as a {@code PENDING} row due immediately and returns the message
     * that was recorded. A task that is already recorded – the same task for the same
     * record – writes nothing and returns a message carrying the same {@code taskId}, so a
     * retried business action cannot queue the work twice.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public BackgroundTaskMessage submit(TaskType type, String recordType, Long recordId, Long customerId) {
        Instant now = clock.instant();
        BackgroundTaskMessage message = BackgroundTaskMessage.of(type, recordType, recordId, customerId,
                actorContext.actor(), actorContext.correlationId(), now);
        if (outboxRepository.existsByTaskId(message.taskId())) {
            return message;
        }
        try {
            outboxRepository.saveAndFlush(BackgroundTaskOutboxEntry.builder()
                    .taskId(message.taskId())
                    .taskType(message.type())
                    .recordType(message.recordType())
                    .recordId(message.recordId())
                    .customerId(message.customerId())
                    .requestedBy(message.requestedBy())
                    .correlationId(message.correlationId())
                    .payload(serialise(message))
                    .status(BackgroundTaskStatus.PENDING)
                    .attempts(0)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .build());
        } catch (DataIntegrityViolationException raceLost) {
            if (!isDuplicateTaskId(raceLost)) {
                throw raceLost;
            }
            // The unique task_id is the backstop for two requests recording the same task
            // at the same time: the other one stored the row, so this one is already
            // submitted and must not fail the business action that asked for it.
        }
        return message;
    }

    /**
     * The stable identity of a task, so a duplicate submission is recognisable: the same
     * task type applied to the same record, and nothing else, decides it.
     */
    public static String taskIdFor(TaskType type, String recordType, Long recordId) {
        return BackgroundTaskMessage.taskIdFor(type, recordType, recordId);
    }

    /** Whether this integrity violation is the unique {@code task_id}, not some other rule. */
    private boolean isDuplicateTaskId(DataIntegrityViolationException failure) {
        String detail = String.valueOf(failure.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        return detail.contains("task_id");
    }

    private String serialise(BackgroundTaskMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The background task " + message.taskId() + " (" + message.type()
                    + ") could not be stored for publishing.", ex);
        }
    }
}
