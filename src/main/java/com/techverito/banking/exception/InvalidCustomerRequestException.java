package com.techverito.banking.exception;

public class InvalidCustomerRequestException extends RuntimeException {
    public InvalidCustomerRequestException(String message) {
        super(message);
    }
}
