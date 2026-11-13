package com.techverito.banking.event;

import com.techverito.banking.entity.AccountStatus;

/**
 * An account's status changed. Published by AccountService.update, and only when
 * the submitted status differs from the stored one.
 */
public record AccountStatusChangedPayload(
        Long accountId,
        AccountStatus previousStatus,
        AccountStatus newStatus
) implements EventPayload {

    @Override
    public EventType eventType() {
        return EventType.ACCOUNT_STATUS_CHANGED;
    }
}
