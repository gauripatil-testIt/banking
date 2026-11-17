package com.techverito.banking.entity;

/**
 * Where an {@link OutboxEvent} is in its journey to the broker.
 *
 * <p>There is deliberately no {@code DELIVERED} value: a delivered row is deleted, because the
 * broker already has it. {@link #FAILED} is terminal and means the event can never be sent (for
 * example an invalid topic), so the row is kept in the database with its event id and failure
 * reason for investigation instead of being retried or dropped. A transient broker failure is not
 * {@code FAILED}: the relay retries it forever and the row stays {@link #PENDING}.</p>
 */
public enum OutboxEventStatus {
    PENDING, FAILED
}
