package com.techverito.banking.config;

import com.techverito.banking.event.task.BackgroundTaskOutboxPublisher;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;

/**
 * Enables the scheduled drain of the background-task outbox and binds its settings,
 * without adding scheduling to {@code BankingApplication} itself.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(BackgroundTaskProperties.class)
public class BackgroundTaskPublishingConfig {

    /**
     * Runs the drain every {@code banking.tasks.drain-interval} that
     * {@link BackgroundTaskProperties#drainInterval()} was bound from, measuring the delay
     * from the end of one drain to the start of the next so a slow drain never overlaps
     * itself.
     */
    @Bean
    public SchedulingConfigurer backgroundTaskDrainScheduler(BackgroundTaskOutboxPublisher publisher,
                                                             BackgroundTaskProperties properties) {
        return taskRegistrar ->
                taskRegistrar.addFixedDelayTask(publisher::drain, properties.drainInterval().toMillis());
    }
}
