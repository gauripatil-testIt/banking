package com.techverito.banking.event.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.entity.BusinessEventOutboxEntry;
import com.techverito.banking.entity.OutboxEventStatus;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.EventType;
import com.techverito.banking.repository.BusinessEventOutboxRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Records a business event in the transactional outbox, in the same transaction as
 * the business change it announces. Nothing here talks to a broker: the business
 * action commits its data and its pending event together, so the action still
 * succeeds while the broker is unreachable and the event is published once it is
 * back.
 *
 * <p>The row holds the serialised envelope, so the drain republishes exactly what the
 * business transaction recorded, and the business key, so per-key ordering is a query
 * rather than a recomputation. The key follows the publisher's rule: an event that
 * names an account is keyed by the account, a customer-only event by the customer.</p>
 */
@Component
public class BusinessEventOutboxRecorder {

    static final String CUSTOMER_KEY_PREFIX = "customer:";

    static final String ACCOUNT_KEY_PREFIX = "account:";

    private final BusinessEventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final EventActorContext actorContext;

    public BusinessEventOutboxRecorder(BusinessEventOutboxRepository outboxRepository,
                                       ObjectMapper objectMapper,
                                       Clock clock,
                                       EventActorContext actorContext) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.actorContext = actorContext;
    }

    /**
     * Builds the envelope for the business change, stores it as a {@code PENDING} row
     * due immediately and returns the event that was recorded.
     */
    public BusinessEvent record(EventType type, Long customerId, Long accountId) {
        BusinessEvent event = BusinessEvent.create(type, customerId, accountId,
                actorContext.actor(), actorContext.correlationId(), clock);
        Instant now = clock.instant();
        outboxRepository.save(BusinessEventOutboxEntry.builder()
                .eventId(event.eventId())
                .eventType(event.type())
                .eventVersion(event.version())
                .occurredAt(event.occurredAt())
                .customerId(event.customerId())
                .accountId(event.accountId())
                .actor(event.actor())
                .correlationId(event.correlationId())
                .businessKey(businessKey(event.customerId(), event.accountId()))
                .payload(serialise(event))
                .status(OutboxEventStatus.PENDING)
                .attempts(0)
                .nextAttemptAt(now)
                .createdAt(now)
                .build());
        return event;
    }

    /**
     * The ordering key: an event that names an account is keyed by the account, so the
     * events of one account keep their order; only a customer-only event is keyed by
     * the customer.
     */
    public static String businessKey(Long customerId, Long accountId) {
        if (accountId != null) {
            return ACCOUNT_KEY_PREFIX + accountId;
        }
        return CUSTOMER_KEY_PREFIX + customerId;
    }

    private String serialise(BusinessEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The event " + event.eventId() + " (" + event.type()
                    + ") could not be stored for publishing.", ex);
        }
    }
}
