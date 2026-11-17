package com.techverito.banking.dto;

import com.techverito.banking.entity.BackgroundTask;

import java.time.Instant;

/**
 * The current state of a submitted background task. {@code publishedAt} is null while
 * the task is {@code PENDING}, that is until RabbitMQ confirmed its message.
 */
public record TaskStatusResponse(
        Long taskId,
        String taskType,
        String status,
        Instant publishedAt
) {
    public static TaskStatusResponse from(BackgroundTask task) {
        return new TaskStatusResponse(task.getId(), task.getTaskType().name(), task.getStatus().name(),
                task.getPublishedAt());
    }
}
