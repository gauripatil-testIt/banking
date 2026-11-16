package com.techverito.banking.config;

/**
 * Which business event bus implementation is active, selected with
 * {@code banking.events.bus}. It defaults to {@link #KAFKA} so any environment other
 * than local development runs the real broker, while {@link #IN_MEMORY} is an explicit
 * choice for a workstation or an automated test.
 */
public enum EventBusType {
    IN_MEMORY, KAFKA
}
