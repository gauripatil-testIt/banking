package com.techverito.banking.dto;

import com.techverito.banking.entity.BackgroundTask;

/**
 * What a caller gets back when slow work was accepted instead of done in the request:
 * the task id to poll on {@code GET /tasks/{taskId}}, the task type and its outbox
 * status, which is {@code PENDING} until RabbitMQ confirmed the message.
 */
public record TaskAcceptedResponse(
        Long taskId,
        String taskType,
        String status
) {
    public static TaskAcceptedResponse from(BackgroundTask task) {
        return new TaskAcceptedResponse(task.getId(), task.getTaskType().name(), task.getStatus().name());
    }
}
