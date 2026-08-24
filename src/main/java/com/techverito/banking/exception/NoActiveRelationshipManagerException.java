package com.techverito.banking.exception;

public class NoActiveRelationshipManagerException extends RuntimeException {
    public NoActiveRelationshipManagerException() {
        super("No active relationship managers are available for assignment");
    }

    public NoActiveRelationshipManagerException(String message) {
        super(message);
    }
}
