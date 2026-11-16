package com.techverito.banking.event;

/**
 * Thrown when an event could not be published to the configured transport. It carries
 * the id of the event that failed, so the failure can be reported and the stored
 * event retried instead of silently disappearing.
 */
public class EventPublishException extends RuntimeException {

    private final String eventId;

    public EventPublishException(String eventId, String reason, Throwable cause) {
        super("The event " + eventId + " could not be published: " + reason, cause);
        this.eventId = eventId;
    }

    /** The id of the event that could not be published. */
    public String getEventId() {
        return eventId;
    }
}
