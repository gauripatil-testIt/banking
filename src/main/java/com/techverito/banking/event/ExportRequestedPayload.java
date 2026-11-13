package com.techverito.banking.event;

/**
 * An export was requested. Declared only: no flow publishes this yet, the export
 * feature does not exist.
 */
public record ExportRequestedPayload(
        Long customerId,
        String exportType
) implements EventPayload {

    @Override
    public EventType eventType() {
        return EventType.EXPORT_REQUESTED;
    }
}
