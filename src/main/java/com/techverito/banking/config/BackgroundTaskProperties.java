package com.techverito.banking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for submitting background tasks and for draining their outbox. The defaults
 * apply unless the environment overrides them, so a deployment that sets nothing
 * publishes to {@code banking.background-tasks}, retries a failed submission up to five
 * times on an exponential schedule – one second after the first failure, then two, four
 * and eight seconds after each further one – two hundred rows per drain, one drain
 * every second.
 */
@ConfigurationProperties(prefix = "banking.tasks")
public record BackgroundTaskProperties(
        @DefaultValue("banking.background-tasks") String exchange,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("PT1S") Duration retryInitialDelay,
        @DefaultValue("2") double retryMultiplier,
        @DefaultValue("200") int batchSize,
        @DefaultValue("PT1S") Duration drainInterval,
        @DefaultValue("PT30S") Duration publishTimeout
) {
}
