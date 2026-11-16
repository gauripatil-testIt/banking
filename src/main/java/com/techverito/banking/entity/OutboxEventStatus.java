package com.techverito.banking.entity;

/**
 * Where an outbox row stands. It is {@link #PENDING} from the moment the business
 * transaction records it until Kafka acknowledges it ({@link #PUBLISHED}), or until
 * its retries are exhausted and it is kept as {@link #FAILED} for investigation.
 */
public enum OutboxEventStatus {
    PENDING, PUBLISHED, FAILED
}
