package com.techverito.banking.event;

import java.time.Instant;
import java.util.UUID;

/**
 * The one envelope every business event is published in. The fields are fixed:
 * what varies between events is the {@link EventPayload} carried in
 * {@code payload}, and its {@code type}/{@code version} describe that payload.
 *
 * <p>{@code actor} and {@code correlationId} are left null by {@link #of} and
 * stamped by the {@link EventBus} when the event is published, so a caller can
 * never forget them.</p>
 */
public record BusinessEvent(
        String eventId,
        EventType type,
        int version,
        Instant occurredAt,
        Long customerId,
        Long accountId,
        String actor,
        String correlationId,
        EventPayload payload
) {

    public BusinessEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("An event id is required.");
        }
        if (type == null) {
            throw new IllegalArgumentException("An event type is required.");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("The time the event occurred is required.");
        }
        if (payload == null) {
            throw new IllegalArgumentException("An event payload is required.");
        }
    }

    /**
     * Builds an envelope for the given payload, minting a unique event id and the
     * occurrence time. The actor and correlation id are stamped on publish.
     */
    public static BusinessEvent of(EventPayload payload, Long customerId, Long accountId) {
        if (payload == null) {
            throw new IllegalArgumentException("An event payload is required.");
        }
        return new BusinessEvent(
                UUID.randomUUID().toString(),
                payload.eventType(),
                payload.version(),
                Instant.now(),
                customerId,
                accountId,
                null,
                null,
                payload
        );
    }

    /**
     * Returns a copy of this event with the actor and correlation id filled in;
     * every other field is carried over unchanged.
     */
    public BusinessEvent stamped(String actor, String correlationId) {
        return new BusinessEvent(eventId, type, version, occurredAt, customerId, accountId,
                actor, correlationId, payload);
    }
}
