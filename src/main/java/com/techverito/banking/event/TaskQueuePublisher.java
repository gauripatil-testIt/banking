package com.techverito.banking.event;

import com.techverito.banking.exception.TaskPublishException;

/**
 * The port the outbox relay publishes a task message through, so no service or controller
 * ever refers to a broker class.
 *
 * <p>Unlike {@link BusinessEventBus#publish(BusinessEvent)}, which is fire-and-forget, this
 * port is only satisfied by a broker acknowledgement: {@link #publish(String, String)}
 * returns <strong>after</strong> RabbitMQ has confirmed that it owns the message, and
 * throws {@link TaskPublishException} in every other case - a nack, an unroutable message,
 * an unreachable broker after the configured retries. That is what lets the relay keep a
 * task queued until it really is on its queue, and what makes a task count as submitted
 * only on confirmation.</p>
 *
 * <p>There is no no-op or in-memory implementation: a task that was not confirmed must
 * stay PENDING, never silently disappear.</p>
 */
public interface TaskQueuePublisher {

    /**
     * Publishes one serialised task message to the queue bound with {@code routingKey}.
     *
     * @param routingKey the task type's routing key, for example {@code task.document-scan}
     * @param messageJson the business event envelope that was written into the outbox row
     * @throws TaskPublishException when the broker did not confirm the message
     */
    void publish(String routingKey, String messageJson);
}
