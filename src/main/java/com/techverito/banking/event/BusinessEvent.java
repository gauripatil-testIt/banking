package com.techverito.banking.event;

import com.techverito.banking.exception.BusinessEventValidationException;

import java.time.Instant;
import java.util.UUID;

/**
 * The one envelope every business event is announced in.
 *
 * <p>{@code type} holds the lower-case dotted wire value of a
 * {@link BusinessEventType} (for example {@code customer.registered}) rather than the
 * enum, so the JSON is the documented format. The record is immutable and validates
 * its own invariants, so an event that exists on the wire is always complete.</p>
 *
 * @param <T> the concrete payload type, chosen by whoever constructs or reads the event
 */
public record BusinessEvent<T extends EventPayload>(
        UUID eventId,
        String type,
        Integer version,
        Instant occurredAt,
        SubjectType subjectType,
        Long subjectId,
        String causedBy,
        String correlationId,
        T payload
) {

    public BusinessEvent {
        if (eventId == null) {
            throw new BusinessEventValidationException("An event id is required.");
        }
        if (type == null || type.isBlank()) {
            throw new BusinessEventValidationException(
                    "An event type is required, for example customer.registered.");
        }
        if (version == null || version < 1) {
            throw new BusinessEventValidationException(
                    "An event version must be 1 or greater, but was " + version + ".");
        }
        if (occurredAt == null) {
            throw new BusinessEventValidationException("The time the event occurred is required.");
        }
        if (subjectType == null || subjectId == null) {
            throw new BusinessEventValidationException(
                    "subjectType (CUSTOMER|ACCOUNT) plus subjectId is required on every event.");
        }
        if (causedBy == null || causedBy.isBlank()) {
            throw new BusinessEventValidationException("The cause of the event (causedBy) is required.");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new BusinessEventValidationException("A correlation id is required on every event.");
        }
        if (payload == null) {
            throw new BusinessEventValidationException("An event payload is required.");
        }
    }
}
