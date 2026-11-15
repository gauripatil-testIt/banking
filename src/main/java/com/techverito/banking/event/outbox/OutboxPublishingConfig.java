package com.techverito.banking.event.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the scheduled drain of the outbox and binds its settings, without adding
 * scheduling to {@code BankingApplication} itself.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxPublishingConfig {

    /**
     * Runs the drain every {@code banking.events.outbox.drain-interval} that
     * {@link OutboxProperties#drainInterval()} was bound from, measuring the delay from
     * the end of one drain to the start of the next so a slow drain never overlaps itself.
     */
    @Bean
    public SchedulingConfigurer outboxDrainScheduler(BusinessEventOutboxPublisher publisher,
                                                     OutboxProperties properties) {
        return taskRegistrar ->
                taskRegistrar.addFixedDelayTask(publisher::drain, properties.drainInterval().toMillis());
    }
}
