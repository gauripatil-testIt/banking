package com.techverito.banking.event;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The in-process bus used for local development and automated tests, where no
 * broker is installed. Delivery is synchronous on the publishing thread, so a test
 * can publish an event and assert what was received immediately afterwards.
 *
 * <p>Registration is append-only and thread-safe; publishing never blocks on a
 * network because there is no network.</p>
 */
public class InMemoryEventBus implements BusinessEventBus {

    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    @Override
    public void publish(BusinessEvent event) {
        for (Subscription subscription : subscriptions) {
            if (subscription.type() == event.type()) {
                subscription.handler().accept(event);
            }
        }
    }

    @Override
    public void subscribe(EventType type, Consumer<BusinessEvent> handler) {
        subscriptions.add(new Subscription(Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(handler, "handler")));
    }

    /** One handler registered for one event type. */
    private record Subscription(EventType type, Consumer<BusinessEvent> handler) {
    }
}
