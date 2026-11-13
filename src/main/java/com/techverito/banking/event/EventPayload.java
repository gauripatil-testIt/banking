package com.techverito.banking.event;

/**
 * The typed body of a {@link BusinessEvent}. Each payload record declares the
 * {@link EventType} it belongs to and inherits the current format version:
 * adding a field keeps the version, a breaking change overrides it.
 */
public interface EventPayload {

    EventType eventType();

    /**
     * The format version of this payload. Overridden only when a change is not
     * backwards compatible; consumers then see a different version and can tell
     * the shapes apart.
     */
    default int version() {
        return 1;
    }
}
