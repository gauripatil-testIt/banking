package com.techverito.banking.exception;

/**
 * A task message could not be handed to RabbitMQ: the broker nacked it, no queue is bound
 * to its routing key, or the broker could not be reached after the configured retries.
 *
 * <p>The relay catches it, leaves the outbox row PENDING and tries again later, so the
 * task is never lost and never reported as submitted when it was not.</p>
 */
public class TaskPublishException extends RuntimeException {

    private final String routingKey;
    private final String eventId;

    public TaskPublishException(String message, String routingKey, String eventId) {
        super(message);
        this.routingKey = routingKey;
        this.eventId = eventId;
    }

    public TaskPublishException(String message, String routingKey, String eventId, Throwable cause) {
        super(message, cause);
        this.routingKey = routingKey;
        this.eventId = eventId;
    }

    /** The routing key the message was published with, for logging and diagnosis. */
    public String getRoutingKey() {
        return routingKey;
    }

    /** The event id of the message that could not be published, for logging and diagnosis. */
    public String getEventId() {
        return eventId;
    }
}
