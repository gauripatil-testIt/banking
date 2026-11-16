package com.techverito.banking.event;

import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.exception.BusinessEventValidationException;

/**
 * Payload of a {@link BusinessEventType#ACCOUNT_STATUS_CHANGED} event.
 */
public record AccountStatusChangedPayload(
        Long accountId,
        String accountNumber,
        AccountStatus previousStatus,
        AccountStatus newStatus
) implements EventPayload {

    public AccountStatusChangedPayload {
        if (accountId == null) {
            throw new BusinessEventValidationException(
                    "An account id is required to announce a status change.");
        }
        if (previousStatus == null) {
            throw new BusinessEventValidationException(
                    "The previous account status is required to announce a status change.");
        }
        if (newStatus == null) {
            throw new BusinessEventValidationException(
                    "The new account status is required to announce a status change.");
        }
    }
}
