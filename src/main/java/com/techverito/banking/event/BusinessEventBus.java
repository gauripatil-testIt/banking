package com.techverito.banking.event;

import java.util.function.Consumer;

/**
 * The one seam business code uses to announce a business change and to listen for
 * one. Nothing in this interface names Kafka, RabbitMQ or any other broker: which
 * transport carries the event is decided by configuration
 * ({@code banking.events.transport}), so a controller or service publishes and
 * subscribes without knowing whether the event leaves the JVM.
 */
public interface BusinessEventBus {

    /**
     * Publishes the event to the configured transport.
     */
    void publish(BusinessEvent event);

    /**
     * Registers a handler for one event type. Handlers are invoked in the order they
     * were registered.
     */
    void subscribe(EventType type, Consumer<BusinessEvent> handler);
}
