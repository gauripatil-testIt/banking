package com.techverito.banking.event.rabbit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.config.TaskQueueProperties;
import com.techverito.banking.event.TaskQueuePublisher;
import com.techverito.banking.exception.TaskPublishException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The RabbitMQ side of task submission: publishes one task message to the task type's
 * routing key and returns only once the broker confirmed it.
 *
 * <p>The confirmation is a publisher confirm ({@code spring.rabbitmq.publisher-confirm-type=correlated}):
 * the template is handed a {@link CorrelationData} whose future completes with the broker's
 * ack or nack. A nack, a message returned as unroutable ({@code publisher-returns} plus a
 * mandatory message) or a confirm that never arrives within the timeout is a
 * {@link TaskPublishException} and never a silent success - the outbox row then stays
 * PENDING for a later relay run.</p>
 *
 * <p>Only a broker that cannot be reached is retried ({@code AmqpConnectException} and the
 * rest of the {@link AmqpException} family), a bounded number of times with an exponential
 * backoff, because that is the transient failure a restart or a blip produces. A broker
 * that rejected the message is not asked again: the next relay run decides.</p>
 *
 * <p>Messages carry the envelope's {@code eventId} as {@code messageId} and its
 * {@code correlationId}, are persistent and declare {@code application/json}, so a consumer
 * (BANK-11) can deduplicate, tie the work to the request and read it as JSON. No customer
 * name or email is ever logged here.</p>
 */
@Component
public class RabbitTaskQueuePublisher implements TaskQueuePublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitTaskQueuePublisher.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** How often a message is tried when the broker cannot be reached. */
    private static final int MAX_ATTEMPTS = 3;

    /** First pause between two attempts; it doubles per attempt, up to {@link #MAX_BACKOFF}. */
    private static final Duration BASE_BACKOFF = Duration.ofMillis(200);

    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);

    /** How long to wait for the broker's confirm before treating the send as failed. */
    private static final Duration CONFIRM_TIMEOUT = Duration.ofSeconds(5);

    private final RabbitTemplate rabbitTemplate;
    private final TaskQueueProperties properties;

    public RabbitTaskQueuePublisher(RabbitTemplate rabbitTemplate, TaskQueueProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(String routingKey, String messageJson) {
        if (routingKey == null || routingKey.isBlank()) {
            throw new TaskPublishException("A task message needs a routing key.", routingKey, null);
        }
        if (messageJson == null || messageJson.isBlank()) {
            throw new TaskPublishException("The JSON of the task message is required.", routingKey, null);
        }

        String exchange = properties.exchange();
        String eventId = jsonField(messageJson, "eventId");
        String correlationId = jsonField(messageJson, "correlationId");

        RuntimeException lastConnectionFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                sendAndAwaitConfirmation(exchange, routingKey, messageJson, eventId, correlationId);
                log.info("Published a background task message: eventId={}, routingKey={}, correlationId={}, exchange={}",
                        eventId, routingKey, correlationId, exchange);
                return;
            } catch (AmqpException ex) {
                lastConnectionFailure = ex;
                log.warn("Could not reach RabbitMQ for a background task message (attempt {}/{}): "
                                + "eventId={}, routingKey={}, correlationId={}, exchange={}",
                        attempt, MAX_ATTEMPTS, eventId, routingKey, correlationId, exchange);
                if (attempt < MAX_ATTEMPTS) {
                    pause(backoff(attempt), routingKey, eventId, ex);
                }
            }
        }
        throw new TaskPublishException("The task message could not be published to RabbitMQ: eventId=" + eventId
                + ", routingKey=" + routingKey + ", correlationId=" + correlationId
                + ". The task stays queued and is retried by the relay.", routingKey, eventId,
                lastConnectionFailure);
    }

    /**
     * Sends one message and waits for the broker's confirm, so a normal return means the
     * broker owns the message.
     */
    private void sendAndAwaitConfirmation(String exchange, String routingKey, String messageJson,
                                          String eventId, String correlationId) {
        CorrelationData correlation = new CorrelationData((eventId != null) ? eventId : routingKey);
        rabbitTemplate.convertAndSend(exchange, routingKey, messageJson,
                messagePostProcessor(eventId, correlationId), correlation);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(CONFIRM_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TaskPublishException("Interrupted while waiting for RabbitMQ to confirm the task message: eventId="
                    + eventId + ", routingKey=" + routingKey + ", correlationId=" + correlationId + ".",
                    routingKey, eventId, ex);
        } catch (ExecutionException | TimeoutException ex) {
            throw new TaskPublishException("RabbitMQ did not confirm the task message: eventId=" + eventId
                    + ", routingKey=" + routingKey + ", correlationId=" + correlationId + ".", routingKey, eventId, ex);
        }

        if (!confirm.isAck()) {
            throw new TaskPublishException("RabbitMQ rejected the task message (nack: " + confirm.getReason()
                    + "): eventId=" + eventId + ", routingKey=" + routingKey + ", correlationId=" + correlationId + ".",
                    routingKey, eventId);
        }

        ReturnedMessage returned = correlation.getReturned();
        if (returned != null) {
            throw new TaskPublishException("The task message was not routed to any queue (reply " + returned.getReplyCode()
                    + " " + returned.getReplyText() + "): eventId=" + eventId + ", routingKey=" + routingKey
                    + ", correlationId=" + correlationId + ".", routingKey, eventId);
        }
    }

    private MessagePostProcessor messagePostProcessor(String eventId, String correlationId) {
        return message -> {
            MessageProperties messageProperties = message.getMessageProperties();
            messageProperties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
            messageProperties.setContentEncoding(StandardCharsets.UTF_8.name());
            messageProperties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            if (eventId != null && !eventId.isBlank()) {
                messageProperties.setMessageId(eventId);
            }
            if (correlationId != null && !correlationId.isBlank()) {
                messageProperties.setCorrelationId(correlationId);
            }
            return message;
        };
    }

    private Duration backoff(int attempt) {
        Duration backoff = BASE_BACKOFF.multipliedBy(1L << (attempt - 1));
        return (backoff.compareTo(MAX_BACKOFF) > 0) ? MAX_BACKOFF : backoff;
    }

    private void pause(Duration backoff, String routingKey, String eventId, Throwable cause) {
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TaskPublishException("Interrupted while retrying the task message: eventId=" + eventId
                    + ", routingKey=" + routingKey + ".", routingKey, eventId, cause);
        }
    }

    /**
     * The envelope's {@code eventId} and {@code correlationId}, read without binding the
     * payload: the publisher only needs them for the message properties and the log line.
     */
    private String jsonField(String messageJson, String field) {
        try {
            JsonNode node = JSON.readTree(messageJson);
            JsonNode value = (node != null) ? node.get(field) : null;
            return (value != null && !value.isNull()) ? value.asText() : null;
        } catch (Exception ex) {
            return null;
        }
    }
}
