package com.techverito.banking.event;

import com.techverito.banking.exception.BusinessEventValidationException;

/**
 * Payload of a {@link BusinessEventType#CUSTOMER_REGISTERED} event.
 */
public record CustomerRegisteredPayload(
        Long customerId,
        String firstName,
        String lastName,
        String email
) implements EventPayload {

    public CustomerRegisteredPayload {
        if (customerId == null) {
            throw new BusinessEventValidationException("A customer id is required to announce a registration.");
        }
        if (email == null || email.isBlank()) {
            throw new BusinessEventValidationException("The customer's email is required to announce a registration.");
        }
    }
}
