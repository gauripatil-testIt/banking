package com.techverito.banking.event;

/**
 * The single seam every business flow publishes and subscribes through. No
 * controller or service may refer to a broker class: which implementation is
 * used behind this interface is decided by configuration
 * ({@code banking.events.bus}), and the in-memory one keeps the application and
 * the tests running with no broker installed.
 */
public interface EventBus {

    /**
     * Publishes an event, stamping the actor and the correlation id of the
     * current request onto it before any subscriber sees it.
     *
     * @param event the envelope built with {@link BusinessEvent#of}
     * @param actor who or what caused the change; required and non-blank
     */
    void publish(BusinessEvent event, String actor);

    /**
     * Registers a subscriber for one event type.
     *
     * @return a handle whose {@link Subscription#cancel()} stops further delivery
     */
    Subscription subscribe(EventType type, EventSubscriber subscriber);

    /**
     * Receives a published event. A subscriber may throw at runtime; the bus
     * isolates it so the other subscribers are still invoked.
     */
    @FunctionalInterface
    interface EventSubscriber {
        void onEvent(BusinessEvent event);
    }

    /**
     * The handle returned by {@link #subscribe}. Cancelling twice is harmless.
     */
    interface Subscription {
        void cancel();
    }
}
