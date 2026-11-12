package com.techverito.banking.dto;

import com.techverito.banking.entity.CustomerDocument;
import com.techverito.banking.entity.DocumentType;

import java.time.Instant;

public record DocumentResponse(
        Long id,
        Long customerId,
        String originalFileName,
        DocumentType documentType,
        long sizeBytes,
        Instant uploadedAt,
        String uploadedBy,
        String note
) {
    public static DocumentResponse from(CustomerDocument d) {
        return new DocumentResponse(
                d.getId(), d.getCustomer().getId(), d.getOriginalFileName(),
                d.getDocumentType(), d.getSizeBytes(), d.getUploadedAt(),
                d.getUploadedBy(), d.getNote()
        );
    }
}
