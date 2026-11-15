package com.techverito.banking.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The one envelope every business change is announced in, whatever the transport.
 *
 * <p>An event always carries its own unique id, its type, the format version, when
 * it happened, who or what caused it and a correlation id. It concerns a customer,
 * an account, or both: {@code customerId} and {@code accountId} are individually
 * optional but at least one has to be present.</p>
 *
 * <p>Unknown JSON fields are ignored when a payload is read, so adding an optional
 * field keeps the current {@link #CURRENT_VERSION}; a breaking change needs a new
 * version and consumers branch on {@code version}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BusinessEvent(
        String eventId,
        EventType type,
        int version,
        Instant occurredAt,
        Long customerId,
        Long accountId,
        String actor,
        String correlationId
) {

    /** The envelope version this build produces. Additive changes keep it. */
    public static final int CURRENT_VERSION = 1;

    public BusinessEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("A unique event id (eventId) is required.");
        }
        if (type == null) {
            throw new IllegalArgumentException("An event type (type) is required.");
        }
        if (version < 1) {
            throw new IllegalArgumentException(
                    "The event version (version) must be 1 or greater, but was " + version + ".");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("The time the event occurred (occurredAt) is required.");
        }
        if (customerId == null && accountId == null) {
            throw new IllegalArgumentException(
                    "An event must reference what it concerns: at least one of customerId or accountId is required.");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException(
                    "The user or system that caused the event (actor) is required.");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("A correlation id (correlationId) is required.");
        }
    }

    /**
     * Builds an event for the current format version, taking its id and time from
     * this application's clock so every publisher names and stamps events the same way.
     */
    public static BusinessEvent create(EventType type, Long customerId, Long accountId, String actor,
                                       String correlationId, Clock clock) {
        return new BusinessEvent(UUID.randomUUID().toString(), type, CURRENT_VERSION, clock.instant(),
                customerId, accountId, actor, correlationId);
    }
}
