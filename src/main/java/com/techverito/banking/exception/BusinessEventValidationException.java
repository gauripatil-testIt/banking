package com.techverito.banking.exception;

public class BusinessEventValidationException extends RuntimeException {
    public BusinessEventValidationException(String message) {
        super(message);
    }
}
