package com.techverito.banking.event;

import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.exception.BusinessEventValidationException;

/**
 * Payload of a {@link BusinessEventType#DOCUMENT_UPLOADED} event.
 */
public record DocumentUploadedPayload(
        Long documentId,
        Long customerId,
        DocumentType documentType,
        String originalFileName,
        long sizeBytes,
        String uploadedBy
) implements EventPayload {

    public DocumentUploadedPayload {
        if (documentId == null) {
            throw new BusinessEventValidationException(
                    "A document id is required to announce an uploaded document.");
        }
        if (documentType == null) {
            throw new BusinessEventValidationException(
                    "A document type is required to announce an uploaded document.");
        }
    }
}
