package com.techverito.banking.entity;

/**
 * The state of a background task's outbox row: {@link #PENDING} until the broker
 * confirmed receipt, {@link #PUBLISHED} once it did, and {@link #FAILED} when the
 * attempts were exhausted – that row is kept, never deleted, so an unsendable task can
 * still be investigated.
 */
public enum BackgroundTaskStatus {
    PENDING, PUBLISHED, FAILED
}
