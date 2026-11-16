package com.techverito.banking.event.task.rabbit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.config.BackgroundTaskProperties;
import com.techverito.banking.event.task.BackgroundTaskMessage;
import com.techverito.banking.event.task.BackgroundTaskPublisher;
import com.techverito.banking.event.task.TaskPublishException;
import com.techverito.banking.event.task.TaskQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The broker-backed submission path: publishes every background task to the one topic
 * exchange, with the routing key of its task type, through a real {@link RabbitTemplate}
 * over the real RabbitMQ client.
 *
 * <p>Publishing is not fire-and-forget. {@link #publish(BackgroundTaskMessage)} sends the
 * task with a {@link CorrelationData} and waits for the broker's confirmation – a
 * publisher confirm, requested per message – so "submitted" means RabbitMQ stored the
 * message rather than "the call returned". A rejection (nack), a message returned as
 * unroutable, a confirmation that does not arrive within
 * {@code banking.tasks.publish-timeout}, or a failed send all surface as a
 * {@link TaskPublishException} carrying the task id and the reason, so the caller can
 * keep the task and retry it.</p>
 *
 * <p>Each message is persistent and carries the task id as its message id, so a task that
 * reached a durable queue survives a broker restart.</p>
 */
@Component
public class RabbitBackgroundTaskPublisher implements BackgroundTaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitBackgroundTaskPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final BackgroundTaskProperties properties;
    private final ObjectMapper objectMapper;

    public RabbitBackgroundTaskPublisher(RabbitTemplate rabbitTemplate,
                                         BackgroundTaskProperties properties,
                                         ObjectMapper objectMapper) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Publishes the task and waits for RabbitMQ's confirmation, so the task counts as
     * submitted only once the broker acknowledged it.
     */
    @Override
    public void publish(BackgroundTaskMessage task) {
        Objects.requireNonNull(task, "task");
        String exchange = properties.exchange();
        String routingKey = TaskQueue.of(task.type()).routingKey();
        CorrelationData correlationData = new CorrelationData(task.taskId());
        try {
            rabbitTemplate.send(exchange, routingKey, messageOf(task), correlationData);
        } catch (RuntimeException ex) {
            log.error("Could not publish a background task: taskId={}, type={}, correlationId={}, exchange={}, "
                            + "routingKey={}", task.taskId(), task.type(), task.correlationId(), exchange,
                    routingKey, ex);
            throw new TaskPublishException(task.taskId(), describe(ex), ex);
        }
        awaitConfirmation(task, exchange, routingKey, correlationData);
    }

    /** The message for a task: the serialised task, persistent and named by its task id. */
    private Message messageOf(BackgroundTaskMessage task) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(task);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("The background task " + task.taskId() + " (" + task.type()
                    + ") could not be serialised for publishing.", ex);
        }
        return MessageBuilder.withBody(body)
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding("UTF-8")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(task.taskId())
                .setCorrelationId(task.correlationId())
                .build();
    }

    /**
     * Waits for the broker's confirmation and turns every outcome other than a confirmed,
     * routed message into a {@link TaskPublishException} naming the task.
     */
    private void awaitConfirmation(BackgroundTaskMessage task, String exchange, String routingKey,
                                   CorrelationData correlationData) {
        CorrelationData.Confirm confirm;
        try {
            confirm = correlationData.getFuture().get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for RabbitMQ to confirm a background task: taskId={}, type={}, "
                            + "correlationId={}, exchange={}, routingKey={}", task.taskId(), task.type(),
                    task.correlationId(), exchange, routingKey, ex);
            throw new TaskPublishException(task.taskId(),
                    "the wait for RabbitMQ's confirmation was interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
            log.error("RabbitMQ did not confirm a background task: taskId={}, type={}, correlationId={}, "
                            + "exchange={}, routingKey={}", task.taskId(), task.type(), task.correlationId(),
                    exchange, routingKey, cause);
            throw new TaskPublishException(task.taskId(), "RabbitMQ did not confirm it within "
                    + properties.publishTimeout().toSeconds() + "s: " + describe(cause), cause);
        }
        ReturnedMessage returned = correlationData.getReturned();
        if (returned != null) {
            String reason = "RabbitMQ returned it as unroutable: exchange=" + returned.getExchange()
                    + ", routingKey=" + returned.getRoutingKey() + ", replyCode=" + returned.getReplyCode()
                    + ", replyText=" + returned.getReplyText();
            log.error("RabbitMQ returned a background task it could not route: taskId={}, type={}, "
                    + "correlationId={}, topic={}", task.taskId(), task.type(), task.correlationId(), reason);
            throw new TaskPublishException(task.taskId(), reason, null);
        }
        if (!confirm.isAck()) {
            String reason = "RabbitMQ rejected it: " + confirm.getReason();
            log.error("RabbitMQ rejected a background task: taskId={}, type={}, correlationId={}, exchange={}, "
                    + "routingKey={}, reason={}", task.taskId(), task.type(), task.correlationId(), exchange,
                    routingKey, confirm.getReason());
            throw new TaskPublishException(task.taskId(), reason, null);
        }
        log.debug("RabbitMQ confirmed a background task: taskId={}, type={}, correlationId={}, exchange={}, "
                        + "routingKey={}", task.taskId(), task.type(), task.correlationId(), exchange, routingKey);
    }

    private String describe(Throwable failure) {
        String message = failure.getMessage();
        return (message != null && !message.isBlank()) ? message : failure.getClass().getSimpleName();
    }
}
