package com.techverito.banking.event;

import com.techverito.banking.entity.AccountType;
import com.techverito.banking.exception.BusinessEventValidationException;

import java.math.BigDecimal;

/**
 * Payload of a {@link BusinessEventType#ACCOUNT_OPENED} event.
 */
public record AccountOpenedPayload(
        Long accountId,
        Long customerId,
        String accountNumber,
        AccountType accountType,
        BigDecimal openingBalance
) implements EventPayload {

    public AccountOpenedPayload {
        if (accountId == null) {
            throw new BusinessEventValidationException("An account id is required to announce an opened account.");
        }
        if (customerId == null) {
            throw new BusinessEventValidationException(
                    "A customer id is required to announce an opened account.");
        }
        if (accountNumber == null || accountNumber.isBlank()) {
            throw new BusinessEventValidationException(
                    "An account number is required to announce an opened account.");
        }
    }
}
