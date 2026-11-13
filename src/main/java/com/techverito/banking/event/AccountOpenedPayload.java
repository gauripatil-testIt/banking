package com.techverito.banking.event;

import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;

/**
 * An account was opened. Published by AccountService.create.
 */
public record AccountOpenedPayload(
        Long accountId,
        Long customerId,
        String accountNumber,
        AccountType type,
        AccountStatus status
) implements EventPayload {

    @Override
    public EventType eventType() {
        return EventType.ACCOUNT_OPENED;
    }
}
