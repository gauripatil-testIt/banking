package com.techverito.banking.event.task;

/**
 * Thrown when a background task could not be published to the configured transport. It
 * carries the id of the task that failed and the reason, so the failure can be recorded
 * on the task's outbox row and the task retried instead of silently disappearing.
 */
public class TaskPublishException extends RuntimeException {

    private final String taskId;

    private final String reason;

    public TaskPublishException(String taskId, String reason, Throwable cause) {
        super("The background task " + taskId + " could not be published: " + reason, cause);
        this.taskId = taskId;
        this.reason = reason;
    }

    /** The id of the task that could not be published. */
    public String taskId() {
        return taskId;
    }

    /** Why the task could not be published. */
    public String reason() {
        return reason;
    }

    /** The id of the task that could not be published, in bean-accessor form. */
    public String getTaskId() {
        return taskId;
    }

    /** Why the task could not be published, in bean-accessor form. */
    public String getReason() {
        return reason;
    }
}
