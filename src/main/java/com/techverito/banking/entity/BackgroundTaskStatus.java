package com.techverito.banking.entity;

/**
 * Where a {@link BackgroundTask} stands in the outbox.
 *
 * <p>A task is {@link #PENDING} from the moment it is written inside the business
 * transaction until RabbitMQ has confirmed its message, and {@link #SUBMITTED} from then
 * on. Only a PENDING row is ever picked up by the relay, so a confirmed task is never
 * published a second time.</p>
 */
public enum BackgroundTaskStatus {
    PENDING, SUBMITTED
}
