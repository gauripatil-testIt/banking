package com.techverito.banking.event;

import com.techverito.banking.exception.BusinessEventValidationException;

import java.time.LocalDate;

/**
 * Payload of a {@link BusinessEventType#STATEMENT_GENERATED} event.
 */
public record StatementGeneratedPayload(
        Long statementId,
        Long accountId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String format
) implements EventPayload {

    public StatementGeneratedPayload {
        if (statementId == null) {
            throw new BusinessEventValidationException(
                    "A statement id is required to announce a generated statement.");
        }
        if (accountId == null) {
            throw new BusinessEventValidationException(
                    "An account id is required to announce a generated statement.");
        }
    }
}
