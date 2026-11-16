package com.techverito.banking.event.task;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * The one message every background task is submitted in, whatever kind of task it is.
 *
 * <p>It names the task ({@code type}), the record it applies to ({@code recordType} and
 * {@code recordId}), who requested it ({@code requestedBy}) and which request it belongs
 * to ({@code correlationId}). It also carries its own {@code taskId} and {@code occurredAt},
 * so a receiver can recognise a task it has already done.</p>
 *
 * <p>The {@code taskId} is derived from the task type and the record, not taken from a
 * random source: submitting the same task for the same record always yields the same
 * {@code taskId}, which is what makes a duplicate submission recognisable instead of
 * being done twice.</p>
 */
public record BackgroundTaskMessage(
        String taskId,
        TaskType type,
        String recordType,
        Long recordId,
        Long customerId,
        String requestedBy,
        String correlationId,
        Instant occurredAt
) {

    public BackgroundTaskMessage {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("A task id (taskId) is required.");
        }
        if (type == null) {
            throw new IllegalArgumentException("A task type (type) is required.");
        }
        if (recordType == null || recordType.isBlank()) {
            throw new IllegalArgumentException("The kind of record the task applies to (recordType) is required.");
        }
        if (recordId == null) {
            throw new IllegalArgumentException("The record the task applies to (recordId) is required.");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new IllegalArgumentException("The user or system that requested the task (requestedBy) is required.");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("A correlation id (correlationId) is required.");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("The time the task was requested (occurredAt) is required.");
        }
    }

    /**
     * Builds the message for one task, naming it from the task type and the record so
     * the same task for the same record always carries the same {@code taskId}.
     */
    public static BackgroundTaskMessage of(TaskType type, String recordType, Long recordId, Long customerId,
                                           String requestedBy, String correlationId, Instant occurredAt) {
        return new BackgroundTaskMessage(taskIdFor(type, recordType, recordId), type, recordType, recordId,
                customerId, requestedBy, correlationId, occurredAt);
    }

    /**
     * The stable identity of a task: the same type applied to the same record, and
     * nothing else, decides it.
     */
    public static String taskIdFor(TaskType type, String recordType, Long recordId) {
        String identity = type + ":" + recordType + ":" + recordId;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
    }
}
