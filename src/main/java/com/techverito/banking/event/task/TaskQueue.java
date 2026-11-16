package com.techverito.banking.event.task;

import java.util.Locale;

/**
 * The one place a task's queue and routing rule are declared: each task type has its
 * own durable queue and its own routing key, so one slow task cannot hold up another.
 *
 * <p>A message is published to the {@code banking.background-tasks} topic exchange with
 * the routing key {@code task.<type>} and bound to the queue
 * {@code banking.background-tasks.<type>}, for example
 * {@code task.welcome-email} → {@code banking.background-tasks.welcome-email}.</p>
 */
public enum TaskQueue {

    WELCOME_EMAIL_QUEUE(TaskType.WELCOME_EMAIL),
    STATEMENT_GENERATION_QUEUE(TaskType.STATEMENT_GENERATION),
    DOCUMENT_EXPORT_QUEUE(TaskType.DOCUMENT_EXPORT),
    DOCUMENT_SCAN_QUEUE(TaskType.DOCUMENT_SCAN);

    /** Every queue name starts with this, followed by the task type in kebab case. */
    static final String QUEUE_NAME_PREFIX = "banking.background-tasks.";

    /** Every routing key starts with this, followed by the task type in kebab case. */
    static final String ROUTING_KEY_PREFIX = "task.";

    private final TaskType taskType;
    private final String queueName;
    private final String routingKey;

    TaskQueue(TaskType taskType) {
        this.taskType = taskType;
        String kebabType = taskType.name().toLowerCase(Locale.ROOT).replace('_', '-');
        this.queueName = QUEUE_NAME_PREFIX + kebabType;
        this.routingKey = ROUTING_KEY_PREFIX + kebabType;
    }

    /** The kind of task this queue carries. */
    public TaskType taskType() {
        return taskType;
    }

    /** The durable queue a task of this type is submitted to. */
    public String queueName() {
        return queueName;
    }

    /** The routing key a task of this type is published with. */
    public String routingKey() {
        return routingKey;
    }

    /**
     * The queue and routing rule of a task type.
     *
     * @throws IllegalArgumentException when the type has no queue, which would otherwise
     *                                  silently publish a task nothing ever reads
     */
    public static TaskQueue of(TaskType type) {
        if (type != null) {
            for (TaskQueue queue : values()) {
                if (queue.taskType == type) {
                    return queue;
                }
            }
        }
        throw new IllegalArgumentException("No background task queue is declared for the task type: " + type + ".");
    }
}
