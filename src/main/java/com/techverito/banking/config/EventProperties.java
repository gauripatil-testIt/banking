package com.techverito.banking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for publishing and subscribing to business events. Nothing here is
 * set in {@code application.properties}: the defaults apply, so the Kafka
 * transport and its topic/consumer group are used unless the environment
 * explicitly selects {@code banking.events.transport=in-memory}.
 */
@ConfigurationProperties(prefix = "banking.events")
public record EventProperties(
        @DefaultValue("KAFKA") EventTransport transport,
        @DefaultValue("banking.business-events") String topic,
        @DefaultValue("banking-event-subscribers") String consumerGroup
) {}
