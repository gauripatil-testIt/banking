package com.techverito.banking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Kafka producer settings for the business event bus. The bootstrap servers are
 * supplied by the environment and have no default, so an unset value stays blank and
 * the application can refuse to start rather than guess.
 */
@ConfigurationProperties(prefix = "banking.events.kafka")
public record KafkaEventProperties(
        String bootstrapServers,
        @DefaultValue("banking-service") String clientId
) {}
