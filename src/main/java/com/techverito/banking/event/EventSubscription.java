package com.techverito.banking.event;

/**
 * A registered subscriber's detach handle. Closing it twice is harmless, so a
 * subscriber may close it from an error path without checking first.
 */
public interface EventSubscription extends AutoCloseable {

    @Override
    void close();
}
