package com.techverito.banking.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link EventBus} for local development and automated tests. It holds
 * the subscribers in memory, delivers synchronously, and defers delivery until
 * the surrounding transaction commits so an event is never announced for work
 * that rolled back.
 *
 * <p>This is not durable delivery: nothing survives the JVM and nothing leaves
 * the process. A real broker adapter is a separate story.</p>
 */
public class InMemoryEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final Map<EventType, List<EventSubscriber>> subscribers = new ConcurrentHashMap<>();
    private final EventRequestContext requestContext;

    public InMemoryEventBus(EventRequestContext requestContext) {
        this.requestContext = requestContext;
    }

    @Override
    public void publish(BusinessEvent event, String actor) {
        if (event == null) {
            throw new IllegalArgumentException("An event is required.");
        }
        if (actor == null || actor.isBlank()) {
            throw new IllegalArgumentException("The actor causing the event is required.");
        }
        BusinessEvent stamped = event.stamped(actor, currentCorrelationId());
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deliver(stamped);
                }
            });
            return;
        }
        deliver(stamped);
    }

    @Override
    public Subscription subscribe(EventType type, EventSubscriber subscriber) {
        Objects.requireNonNull(type, "An event type is required to subscribe.");
        Objects.requireNonNull(subscriber, "A subscriber is required.");
        List<EventSubscriber> forType = subscribers.computeIfAbsent(type, key -> new CopyOnWriteArrayList<>());
        forType.add(subscriber);
        return () -> forType.remove(subscriber);
    }

    private void deliver(BusinessEvent event) {
        List<EventSubscriber> forType = subscribers.get(event.type());
        if (forType == null || forType.isEmpty()) {
            return;
        }
        for (EventSubscriber subscriber : List.copyOf(forType)) {
            try {
                subscriber.onEvent(event);
            } catch (RuntimeException ex) {
                log.warn("A business event subscriber failed for event {} of type {}",
                        event.eventId(), event.type(), ex);
            }
        }
    }

    private String currentCorrelationId() {
        String correlationId = (requestContext != null) ? requestContext.correlationId() : null;
        return (correlationId == null || correlationId.isBlank())
                ? UUID.randomUUID().toString()
                : correlationId;
    }
}
