package com.techverito.banking.config;

import com.techverito.banking.event.task.TaskQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * The RabbitMQ topology behind background tasks: one topic exchange, one durable queue
 * per task type and one binding per queue, declared at startup so a freshly started
 * broker has every queue before the first message.
 *
 * <p>The names come from {@link TaskQueue}, which is the single place the per-type queue
 * name and routing key live, so the topology and the publisher cannot disagree about
 * where a task goes. All four queues are durable, matching the persistent messages the
 * publisher sends: a task already queued survives a broker restart.</p>
 *
 * <p>The declarations are made when RabbitMQ is reachable; when it is not, the context
 * still starts, nothing is lost (a task waits in its outbox row) and the topology is
 * declared as soon as the broker is back.</p>
 */
@Configuration
@EnableConfigurationProperties(BackgroundTaskProperties.class)
public class BackgroundTaskRabbitConfig {

    /** The exchange every background task is published to. */
    @Bean
    public TopicExchange backgroundTaskExchange(BackgroundTaskProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }

    @Bean
    public Queue welcomeEmailQueue() {
        return QueueBuilder.durable(TaskQueue.WELCOME_EMAIL_QUEUE.queueName()).build();
    }

    @Bean
    public Queue statementGenerationQueue() {
        return QueueBuilder.durable(TaskQueue.STATEMENT_GENERATION_QUEUE.queueName()).build();
    }

    @Bean
    public Queue documentExportQueue() {
        return QueueBuilder.durable(TaskQueue.DOCUMENT_EXPORT_QUEUE.queueName()).build();
    }

    @Bean
    public Queue documentScanQueue() {
        return QueueBuilder.durable(TaskQueue.DOCUMENT_SCAN_QUEUE.queueName()).build();
    }

    /**
     * One binding per task type: the exchange routes the task type's routing key to the
     * task type's own queue, so one slow task cannot hold up another.
     *
     * <p>The bindings are handed over as a single {@link Declarables} bean because that is
     * what RabbitMQ's admin discovers and declares; they are declared after the queues and
     * the exchange, which are {@link Declarable} beans in their own right.</p>
     */
    @Bean
    public Declarables taskBindings(TopicExchange backgroundTaskExchange,
                                    @Qualifier("welcomeEmailQueue") Queue welcomeEmailQueue,
                                    @Qualifier("statementGenerationQueue") Queue statementGenerationQueue,
                                    @Qualifier("documentExportQueue") Queue documentExportQueue,
                                    @Qualifier("documentScanQueue") Queue documentScanQueue) {
        List<Declarable> bindings = new ArrayList<>();
        bindings.add(bind(welcomeEmailQueue, backgroundTaskExchange, TaskQueue.WELCOME_EMAIL_QUEUE));
        bindings.add(bind(statementGenerationQueue, backgroundTaskExchange,
                TaskQueue.STATEMENT_GENERATION_QUEUE));
        bindings.add(bind(documentExportQueue, backgroundTaskExchange, TaskQueue.DOCUMENT_EXPORT_QUEUE));
        bindings.add(bind(documentScanQueue, backgroundTaskExchange, TaskQueue.DOCUMENT_SCAN_QUEUE));
        return new Declarables(bindings);
    }

    private Binding bind(Queue queue, TopicExchange exchange, TaskQueue taskQueue) {
        return BindingBuilder.bind(queue).to(exchange).with(taskQueue.routingKey());
    }
}
