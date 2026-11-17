package com.techverito.banking.event;

import com.techverito.banking.config.EventBusProperties;
import com.techverito.banking.entity.OutboxEvent;
import com.techverito.banking.repository.OutboxEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * How business code announces a change: it records the event in the durable outbox instead of
 * talking to a broker.
 *
 * <p>The row is written in the <em>caller's</em> transaction - that is why
 * {@link #record} is {@code MANDATORY} - so it commits or rolls back together with the business
 * change it announces. A broker outage therefore cannot fail or lose a customer action: the row
 * simply waits for the relay. The stored payload is the BANK-6 wire JSON, written once and sent
 * verbatim, so a retried send can never carry a different event id.</p>
 *
 * <p>Nothing in this class, or in the services calling it, refers to Kafka: the topic comes from
 * {@link EventBusProperties} and the ordering key is the subject, so a later change of the broker
 * is a configuration change.</p>
 */
@Component
public class BusinessEventOutbox {

    /** The cause recorded for a change the application makes without an authenticated user. */
    public static final String SYSTEM_CAUSE = "system";

    private final OutboxEventRepository outboxEventRepository;
    private final BusinessEventFactory eventFactory;
    private final BusinessEventJson json;
    private final EventBusProperties properties;
    private final Clock clock;

    public BusinessEventOutbox(OutboxEventRepository outboxEventRepository,
                               BusinessEventFactory eventFactory,
                               BusinessEventJson json,
                               EventBusProperties properties,
                               Clock clock) {
        this.outboxEventRepository = outboxEventRepository;
        this.eventFactory = eventFactory;
        this.json = json;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Stores one outbox row for the event, inside the transaction of the business change.
     *
     * @throws org.springframework.transaction.IllegalTransactionStateException when called outside a
     *         transaction, so an event can never be recorded without the change it describes
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public <T extends EventPayload> void record(BusinessEventType type, SubjectType subjectType, Long subjectId,
                                                String causedBy, String correlationId, T payload) {
        BusinessEvent<T> event = eventFactory.create(type, subjectType, subjectId, causedBy, correlationId, payload);
        String orderingKey = orderingKey(subjectType, subjectId);
        Instant now = Instant.now(clock);
        outboxEventRepository.save(OutboxEvent.builder()
                .eventId(event.eventId())
                .eventType(event.type())
                .eventVersion(event.version())
                .subjectType(subjectType)
                .subjectId(subjectId)
                .sequenceNo(outboxEventRepository.nextSequenceNo(orderingKey))
                .orderingKey(orderingKey)
                .topic(properties.topic())
                .payload(json.toJson(event))
                .createdAt(now)
                .nextAttemptAt(now)
                .build());
    }

    /**
     * The documented ordering key, {@code <subjectType>-<subjectId>} (for example
     * {@code ACCOUNT-42}). It is also the Kafka partition key, so every event of one customer or
     * one account lands on the same partition in the order it happened.
     */
    private String orderingKey(SubjectType subjectType, Long subjectId) {
        return subjectType.name() + "-" + subjectId;
    }
}
