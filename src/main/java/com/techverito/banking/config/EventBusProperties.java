package com.techverito.banking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Selects the {@link com.techverito.banking.event.EventBus} implementation.
 * The in-memory bus is the only supported value until a broker adapter lands.
 */
@ConfigurationProperties(prefix = "banking.events")
public record EventBusProperties(
        @DefaultValue("in-memory") String bus
) {}
