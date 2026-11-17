package com.techverito.banking.event.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the scheduled outbox relay.
 *
 * <p>{@code pollIntervalMs} is how often a relay pass looks for due rows, {@code baseDelayMs} and
 * {@code maxDelayMs} shape the exponential backoff a transient send failure is rescheduled with, and
 * {@code sendTimeoutSeconds} is how long one attempt waits for the broker's acknowledgement before
 * it is treated as failed (and retried later with the same row and the same event id).</p>
 */
@ConfigurationProperties(prefix = "banking.events.outbox")
public record OutboxRelayProperties(
        @DefaultValue("5000") long pollIntervalMs,
        @DefaultValue("5000") long baseDelayMs,
        @DefaultValue("300000") long maxDelayMs,
        @DefaultValue("10") int sendTimeoutSeconds
) {
}
