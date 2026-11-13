package com.techverito.banking.event;

/**
 * The business events announced by the application. BANK-6 defines the six
 * types below; a new type is a new constant, never a renamed one.
 */
public enum EventType {
    CUSTOMER_REGISTERED,
    ACCOUNT_OPENED,
    ACCOUNT_STATUS_CHANGED,
    DOCUMENT_UPLOADED,
    EXPORT_REQUESTED,
    STATEMENT_GENERATED
}
