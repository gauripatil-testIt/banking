package com.techverito.banking.event;

import com.techverito.banking.entity.DocumentType;

/**
 * A customer document was uploaded. Published by DocumentService.upload, with the
 * uploader as the actor.
 */
public record DocumentUploadedPayload(
        Long documentId,
        DocumentType documentType,
        String originalFileName,
        long sizeBytes,
        String uploadedBy
) implements EventPayload {

    @Override
    public EventType eventType() {
        return EventType.DOCUMENT_UPLOADED;
    }
}
