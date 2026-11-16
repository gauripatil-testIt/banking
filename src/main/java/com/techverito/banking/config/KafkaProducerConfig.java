package com.techverito.banking.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Builds the Kafka producer used to announce business events. Active unless
 * {@code banking.events.bus=in-memory}, so a deployment that forgot its broker settings
 * fails here at startup with a readable message instead of silently dropping events.
 *
 * The producer waits for all in-sync replicas and turns on idempotence, so a retried
 * send cannot duplicate an event within the producer session. Reliable end-to-end
 * delivery (outbox, retries, dead letters) is BANK-9, not this configuration.
 */
@Configuration
@ConditionalOnProperty(prefix = "banking.events", name = "bus", havingValue = "kafka", matchIfMissing = true)
public class KafkaProducerConfig {

    @Bean
    public ProducerFactory<String, String> producerFactory(KafkaEventProperties properties) {
        String bootstrapServers = properties.bootstrapServers();
        if (isBlank(bootstrapServers)) {
            throw new IllegalStateException(missingSettingsMessage(bootstrapServers));
        }
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers.trim());
        config.put(ProducerConfig.CLIENT_ID_CONFIG, properties.clientId());
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String missingSettingsMessage(String bootstrapServers) {
        return "The business event bus is set to Kafka, but it is not configured: "
                + "banking.events.kafka.bootstrap-servers must be set from the environment"
                + " (bootstrap-servers=" + (isBlank(bootstrapServers) ? "not set" : bootstrapServers) + "). "
                + "Set banking.events.bus=in-memory to announce events in memory on a workstation or in a test.";
    }
}
