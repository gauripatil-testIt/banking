package com.techverito.banking.event;

/**
 * What the business event concerns: a customer or one of their accounts. Every
 * event carries this pair together with its {@code subjectId}.
 */
public enum SubjectType {
    CUSTOMER, ACCOUNT
}
