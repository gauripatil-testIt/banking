package com.techverito.banking.event;

/**
 * The business changes announced on the event bus. The constant name is the wire
 * value, so {@link #DOCUMENT_UPLOADED} appears verbatim as {@code "DOCUMENT_UPLOADED"}
 * in the payload and a consumer can switch on it without a lookup table.
 */
public enum EventType {

    CUSTOMER_REGISTERED,
    ACCOUNT_OPENED,
    ACCOUNT_STATUS_CHANGED,
    DOCUMENT_UPLOADED,
    EXPORT_REQUESTED,
    STATEMENT_GENERATED
}
