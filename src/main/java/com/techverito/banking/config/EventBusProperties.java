package com.techverito.banking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the business event bus. The implementation is chosen by
 * {@code banking.events.bus} and every implementation publishes to
 * {@code banking.events.topic}.
 */
@ConfigurationProperties(prefix = "banking.events")
public record EventBusProperties(
        @DefaultValue("KAFKA") EventBusType bus,
        @DefaultValue("banking.events") String topic
) {}
