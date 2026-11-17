package com.techverito.banking.event;

import com.techverito.banking.exception.BusinessEventValidationException;

/**
 * Payload of a {@link BusinessEventType#DOCUMENT_SCAN_REQUESTED} event.
 */
public record DocumentScanRequestedPayload(
        Long documentId,
        Long customerId,
        String originalFileName
) implements EventPayload {

    public DocumentScanRequestedPayload {
        if (documentId == null) {
            throw new BusinessEventValidationException(
                    "A document id is required to request a document scan.");
        }
        if (customerId == null) {
            throw new BusinessEventValidationException(
                    "A customer id is required to request a document scan.");
        }
    }
}
