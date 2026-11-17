package com.techverito.banking.event;

import com.techverito.banking.exception.BusinessEventValidationException;

/**
 * Payload of a {@link BusinessEventType#WELCOME_EMAIL_REQUESTED} event.
 */
public record WelcomeEmailRequestedPayload(
        Long customerId,
        String email,
        String requestedBy
) implements EventPayload {

    public WelcomeEmailRequestedPayload {
        if (customerId == null) {
            throw new BusinessEventValidationException(
                    "A customer id is required to request a welcome email.");
        }
        if (email == null || email.isBlank()) {
            throw new BusinessEventValidationException(
                    "The customer's email is required to request a welcome email.");
        }
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new BusinessEventValidationException(
                    "The name of the user requesting the welcome email (requestedBy) is required.");
        }
    }
}
