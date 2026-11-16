package com.techverito.banking.event;

import com.techverito.banking.exception.BusinessEventValidationException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Stamps the part of an envelope the announcer must not choose: a fresh
 * {@code eventId}, the time from the application {@link Clock} and the type/version
 * pair taken from {@link BusinessEventType}. Everything that describes the specific
 * change - the subject, the cause, the correlation id and the payload - is passed
 * straight through.
 */
@Component
public class BusinessEventFactory {

    private final Clock clock;

    public BusinessEventFactory(Clock clock) {
        this.clock = clock;
    }

    public <T extends EventPayload> BusinessEvent<T> create(BusinessEventType type, SubjectType subjectType,
                                                            Long subjectId, String causedBy, String correlationId,
                                                            T payload) {
        if (type == null) {
            throw new BusinessEventValidationException("An event type is required, for example customer.registered.");
        }
        return new BusinessEvent<>(
                UUID.randomUUID(),
                type.getWireValue(),
                type.getCurrentVersion(),
                Instant.now(clock),
                subjectType,
                subjectId,
                causedBy,
                correlationId,
                payload);
    }
}
