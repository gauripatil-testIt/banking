package com.techverito.banking.dto;

import com.techverito.banking.entity.Account;
import com.techverito.banking.event.task.BackgroundTaskMessage;
import com.techverito.banking.event.task.TaskType;

/**
 * The answer to a statement request: the background task that was queued for the
 * account, so a caller can follow the work it just asked for.
 */
public record StatementResponse(String taskId, TaskType taskType, Long accountId) {

    /** The accepted statement request of an account. */
    public static StatementResponse from(Account account, BackgroundTaskMessage task) {
        return new StatementResponse(task.taskId(), task.type(), account.getId());
    }
}
