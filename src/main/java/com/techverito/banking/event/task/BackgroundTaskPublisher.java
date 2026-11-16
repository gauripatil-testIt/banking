package com.techverito.banking.event.task;

/**
 * The one seam business code uses to hand a slow piece of work to a background worker.
 * Nothing in this interface names RabbitMQ or any other broker: which transport carries
 * the task is decided by the implementation, so a service submits a task without knowing
 * where it is queued.
 *
 * <p>There is deliberately no in-memory implementation of this interface, matching the
 * requirement that the application has no stub publishing path: the only implementation
 * in the application talks to the real broker, and the tests substitute their own double.</p>
 */
public interface BackgroundTaskPublisher {

    /**
     * Publishes the task and returns only once the broker confirmed that it received it.
     *
     * @throws TaskPublishException when the broker did not confirm the task, so the caller
     *                              can keep it and retry instead of losing it
     */
    void publish(BackgroundTaskMessage task);
}
