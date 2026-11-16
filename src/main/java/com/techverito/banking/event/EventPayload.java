package com.techverito.banking.event;

/**
 * Marker for the typed payload carried behind a {@link BusinessEvent} envelope.
 *
 * <p>There is one payload record per {@link BusinessEventType}. The interface is
 * sealed so the complete set of payloads this application can announce is visible
 * in one place, and Jackson is never asked to guess a payload type: a reader
 * supplies the concrete payload class when it deserialises.</p>
 */
public sealed interface EventPayload permits CustomerRegisteredPayload, AccountOpenedPayload,
        AccountStatusChangedPayload, DocumentUploadedPayload, ExportRequestedPayload,
        StatementGeneratedPayload {
}
