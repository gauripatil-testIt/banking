package com.techverito.banking.event;

import com.techverito.banking.entity.ExportType;
import com.techverito.banking.exception.BusinessEventValidationException;

/**
 * Payload of a {@link BusinessEventType#EXPORT_REQUESTED} event.
 */
public record ExportRequestedPayload(
        ExportType exportType,
        String exportedBy,
        String fileName
) implements EventPayload {

    public ExportRequestedPayload {
        if (exportType == null) {
            throw new BusinessEventValidationException(
                    "An export type is required to announce an export request.");
        }
        if (exportedBy == null || exportedBy.isBlank()) {
            throw new BusinessEventValidationException(
                    "The name of the user running the export (exportedBy) is required.");
        }
    }
}
