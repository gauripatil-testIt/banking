package com.techverito.banking.event;

/**
 * Receives the events a subscriber registered for, with the payload already typed as
 * the class it subscribed with.
 *
 * @param <T> the payload type this handler understands
 */
@FunctionalInterface
public interface BusinessEventHandler<T extends EventPayload> {

    void handle(BusinessEvent<T> event);
}
