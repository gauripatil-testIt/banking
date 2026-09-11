package com.techverito.banking.exception;

public class OverRefundException extends RuntimeException {
    public OverRefundException(String message) {
        super(message);
    }
}
