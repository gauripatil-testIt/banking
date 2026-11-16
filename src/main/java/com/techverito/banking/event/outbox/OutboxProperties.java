package com.techverito.banking.event.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for draining the transactional outbox. The defaults apply unless the
 * environment overrides them, so a deployment that sets nothing retries a failed
 * event up to five times on an exponential schedule – one second after the first
 * failure, then two, four and eight seconds after each further one – two hundred
 * rows per drain, one drain every second.
 */
@ConfigurationProperties(prefix = "banking.events.outbox")
public record OutboxProperties(
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT1S") Duration retryInitialDelay,
        @DefaultValue("2") double retryMultiplier,
        @DefaultValue("200") int batchSize,
        @DefaultValue("PT1S") Duration drainInterval
) {

    /** The documented default of {@code banking.events.outbox.drain-interval}. */
    public static final Duration DEFAULT_DRAIN_INTERVAL = Duration.ofSeconds(1);
}
