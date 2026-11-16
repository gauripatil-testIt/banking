package com.techverito.banking.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The bus used when no broker is available: local development and automated tests.
 *
 * <p>Selected only when {@code banking.events.bus=in-memory} is set, the same way the
 * document storage backend is selected. Delivery is synchronous and in subscription
 * order, so a test can publish an event and assert immediately afterwards. A handler
 * that throws is logged and does not stop delivery to the other subscribers or fail
 * the publisher, because this bus stands in for a broker during local runs.</p>
 */
@Component
@ConditionalOnProperty(prefix = "banking.events", name = "bus", havingValue = "in-memory")
public class InMemoryBusinessEventBus implements BusinessEventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryBusinessEventBus.class);

    private final List<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();

    public InMemoryBusinessEventBus() {
        log.warn("The in-memory business event bus is active. It is for local development and automated tests "
                + "only and delivers nothing outside this process; any other environment must run the Kafka "
                + "implementation with banking.events.bus=kafka.");
    }

    @Override
    public void publish(BusinessEvent<? extends EventPayload> event) {
        for (Subscription<?> subscription : subscriptions) {
            if (!subscription.matches(event)) {
                continue;
            }
            try {
                subscription.deliver(event);
            } catch (RuntimeException ex) {
                log.error("A business event handler failed: eventId={}, type={}, correlationId={}",
                        event.eventId(), event.type(), event.correlationId(), ex);
            }
        }
    }

    @Override
    public <T extends EventPayload> EventSubscription subscribe(BusinessEventType type, int version,
                                                               Class<T> payloadType,
                                                               BusinessEventHandler<T> handler) {
        Subscription<T> subscription = new Subscription<>(type, version, payloadType, handler);
        subscriptions.add(subscription);
        return subscription;
    }

    /**
     * One registered handler, matched on the type's wire value and the exact version,
     * so a v1 subscriber is never handed a v2 event of the same type.
     */
    private final class Subscription<T extends EventPayload> implements EventSubscription {

        private final String typeWireValue;
        private final int version;
        private final Class<T> payloadType;
        private final BusinessEventHandler<T> handler;

        private Subscription(BusinessEventType type, int version, Class<T> payloadType,
                             BusinessEventHandler<T> handler) {
            this.typeWireValue = type.getWireValue();
            this.version = version;
            this.payloadType = payloadType;
            this.handler = handler;
        }

        private boolean matches(BusinessEvent<? extends EventPayload> event) {
            return typeWireValue.equals(event.type()) && version == event.version();
        }

        private void deliver(BusinessEvent<? extends EventPayload> event) {
            T payload = payloadType.cast(event.payload());
            handler.handle(new BusinessEvent<>(
                    event.eventId(),
                    event.type(),
                    event.version(),
                    event.occurredAt(),
                    event.subjectType(),
                    event.subjectId(),
                    event.causedBy(),
                    event.correlationId(),
                    payload));
        }

        @Override
        public void close() {
            subscriptions.remove(this);
        }
    }
}
