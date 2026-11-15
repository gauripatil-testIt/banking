package com.techverito.banking.config;

/**
 * Which business-event bus implementation is active, selected with
 * {@code banking.events.transport}. It defaults to {@link #KAFKA}: the in-memory
 * transport exists only for local development and automated tests, so a
 * deployment that forgets the setting gets the real broker rather than silently
 * keeping events inside one JVM.
 */
public enum EventTransport {
    IN_MEMORY, KAFKA
}
