package com.techverito.banking.exception;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String message) {
        super(message);
    }

    public InsufficientFundsException(Long accountId) {
        super("Insufficient funds in account with id: " + accountId);
    }
}
