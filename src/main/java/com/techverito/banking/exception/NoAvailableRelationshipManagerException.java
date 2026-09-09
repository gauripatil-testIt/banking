package com.techverito.banking.exception;

public class NoAvailableRelationshipManagerException extends RuntimeException {

    public NoAvailableRelationshipManagerException(String message) {
        super(message);
    }

    public NoAvailableRelationshipManagerException() {
        super();
    }
}
