package com.techverito.banking.event;

import com.techverito.banking.entity.CustomerStatus;

/**
 * A customer was registered. Published by CustomerService.create.
 */
public record CustomerRegisteredPayload(
        Long customerId,
        String email,
        CustomerStatus status
) implements EventPayload {

    @Override
    public EventType eventType() {
        return EventType.CUSTOMER_REGISTERED;
    }
}
