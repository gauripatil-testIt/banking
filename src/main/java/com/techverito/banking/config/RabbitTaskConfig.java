package com.techverito.banking.config;

import com.techverito.banking.entity.BackgroundTaskType;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * The RabbitMQ topology for background tasks: one durable topic exchange, one durable
 * queue per {@link BackgroundTaskType} and one durable binding per queue, so a message
 * routed to one task type can never land on another task type's queue.
 *
 * <p>Everything is durable and nothing is auto-delete, so a queued task survives a
 * broker restart. The topology is declared by Spring AMQP when the application connects,
 * which makes it idempotent: reconnecting after a restart recreates nothing that already
 * exists. Queue names and routing keys come from {@link TaskQueueProperties}, one
 * routing-key property per task type.</p>
 *
 * <p>This class only describes the broker side; publishing lives in
 * {@code com.techverito.banking.event.rabbit}, so no controller or service ever refers to
 * a broker class.</p>
 */
@Configuration
public class RabbitTaskConfig {

    @Bean
    public Declarables taskTopology(TaskQueueProperties properties) {
        TopicExchange exchange = new TopicExchange(properties.exchange(), true, false);

        List<Queue> queues = new ArrayList<>();
        List<Binding> bindings = new ArrayList<>();
        for (BackgroundTaskType taskType : BackgroundTaskType.values()) {
            Queue queue = new Queue(queueName(taskType, properties), true);
            queues.add(queue);
            bindings.add(BindingBuilder.bind(queue).to(exchange).with(properties.routingKey(taskType)));
        }

        List<Declarable> declarables = new ArrayList<>();
        declarables.add(exchange);
        declarables.addAll(queues);
        declarables.addAll(bindings);
        return new Declarables(declarables);
    }

    private String queueName(BackgroundTaskType taskType, TaskQueueProperties properties) {
        return switch (taskType) {
            case WELCOME_EMAIL -> properties.welcomeEmailQueue();
            case STATEMENT_GENERATION -> properties.statementGenerationQueue();
            case DOCUMENT_EXPORT -> properties.documentExportQueue();
            case DOCUMENT_SCAN -> properties.documentScanQueue();
        };
    }
}
