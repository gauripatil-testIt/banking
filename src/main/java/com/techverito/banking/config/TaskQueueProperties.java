package com.techverito.banking.config;

import com.techverito.banking.entity.BackgroundTaskType;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * The RabbitMQ task-queue topology and the relay that feeds it: one topic exchange,
 * one durable queue per background task type and the settings of the scheduled outbox
 * relay. Every name is a property with the documented value as its default, so an
 * environment can rename a queue or a routing key without a code change.
 *
 * <p>No broker host, port, credential or other connection setting lives here: those are
 * Spring Boot's own {@code spring.rabbitmq.*} properties and come from the environment.</p>
 */
@ConfigurationProperties(prefix = "banking.tasks")
public record TaskQueueProperties(
        @DefaultValue("banking.tasks") String exchange,
        @DefaultValue("banking.tasks.welcome-email") String welcomeEmailQueue,
        @DefaultValue("banking.tasks.statement-generation") String statementGenerationQueue,
        @DefaultValue("banking.tasks.document-export") String documentExportQueue,
        @DefaultValue("banking.tasks.document-scan") String documentScanQueue,
        @DefaultValue("task.welcome-email") String welcomeEmailRoutingKey,
        @DefaultValue("task.statement-generation") String statementGenerationRoutingKey,
        @DefaultValue("task.document-export") String documentExportRoutingKey,
        @DefaultValue("task.document-scan") String documentScanRoutingKey,
        @DefaultValue("2s") Duration relayInterval,
        @DefaultValue("true") boolean relayEnabled
) {

    /**
     * The routing key of one task type, so the key a queue is bound with and the key its
     * messages are published with can never disagree, and an environment can override it.
     */
    public String routingKey(BackgroundTaskType taskType) {
        return switch (taskType) {
            case WELCOME_EMAIL -> welcomeEmailRoutingKey;
            case STATEMENT_GENERATION -> statementGenerationRoutingKey;
            case DOCUMENT_EXPORT -> documentExportRoutingKey;
            case DOCUMENT_SCAN -> documentScanRoutingKey;
        };
    }
}
