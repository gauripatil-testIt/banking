package com.techverito.banking.event;

/**
 * The single seam business code publishes and subscribes through, so no controller or
 * service ever refers to a broker class.
 *
 * <p>Which implementation is active is chosen by configuration
 * ({@code banking.events.bus}); swapping the in-memory bus for a real broker is a
 * configuration change, not a code change. This interface deliberately exposes no
 * broker type in its signatures.</p>
 */
public interface BusinessEventBus {

    /**
     * Announces an event. Implementations decide whether delivery waits for the
     * subscriber (in-memory) or only hands the event to the broker.
     */
    void publish(BusinessEvent<? extends EventPayload> event);

    /**
     * Registers a handler for one type and one version of an event. Subscribing to an
     * explicit version is what makes "a breaking change needs a new event version"
     * enforceable: a v1 handler is never handed a v2 event.
     *
     * @return a subscription whose {@link EventSubscription#close()} detaches the handler
     */
    <T extends EventPayload> EventSubscription subscribe(BusinessEventType type, int version, Class<T> payloadType,
                                                         BusinessEventHandler<T> handler);
}
