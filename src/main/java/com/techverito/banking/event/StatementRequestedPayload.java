package com.techverito.banking.event;

import com.techverito.banking.exception.BusinessEventValidationException;

import java.time.LocalDate;

/**
 * Payload of a {@link BusinessEventType#STATEMENT_REQUESTED} event.
 */
public record StatementRequestedPayload(
        Long accountId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String requestedBy
) implements EventPayload {

    public StatementRequestedPayload {
        if (accountId == null) {
            throw new BusinessEventValidationException(
                    "An account id is required to request a statement.");
        }
        if (periodStart == null) {
            throw new BusinessEventValidationException(
                    "The start of the statement period (periodStart) is required.");
        }
        if (periodEnd == null) {
            throw new BusinessEventValidationException(
                    "The end of the statement period (periodEnd) is required.");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new BusinessEventValidationException(
                    "The name of the user requesting the statement (requestedBy) is required.");
        }
        if (periodStart.isAfter(periodEnd)) {
            throw new BusinessEventValidationException(
                    "The start of the statement period (periodStart) must not be after its end (periodEnd).");
        }
    }
}
