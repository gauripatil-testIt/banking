package com.techverito.banking.event.task.rabbit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.techverito.banking.config.BackgroundTaskProperties;
import com.techverito.banking.event.task.BackgroundTaskMessage;
import com.techverito.banking.event.task.TaskPublishException;
import com.techverito.banking.event.task.TaskQueue;
import com.techverito.banking.event.task.TaskType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

/**
 * The RabbitMQ adapter against a mocked {@link RabbitTemplate}: which exchange and routing
 * key a task is published with, that a publish only returns once the broker confirmed it,
 * and that a nack, an unroutable return or a confirmation that never arrives fails with the
 * task id. No broker, no Docker and no network are involved – the real broker round trip is
 * covered by {@link BackgroundTaskRabbitContainerTest}.
 */
class RabbitBackgroundTaskPublisherTest {

    private static final String EXCHANGE = "banking.background-tasks";

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private RabbitBackgroundTaskPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new RabbitBackgroundTaskPublisher(rabbitTemplate, properties(Duration.ofSeconds(5)), objectMapper);
    }

    private BackgroundTaskProperties properties(Duration publishTimeout) {
        return new BackgroundTaskProperties(EXCHANGE, 5, Duration.ofSeconds(1), 2, 200, Duration.ofSeconds(1),
                publishTimeout);
    }

    private BackgroundTaskMessage task(TaskType type) {
        return BackgroundTaskMessage.of(type, "customer", 1L, 1L, "ops.user", "corr-bank-8", NOW);
    }

    /** Stubs the send to complete the publisher's confirm future with the given outcome. */
    private void givenConfirmation(boolean ack, String reason) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(ack, reason));
            return null;
        }).when(rabbitTemplate).send(eq(EXCHANGE), anyString(), any(Message.class), any(CorrelationData.class));
    }

    /** Stubs the send to complete the confirm only after a delay, as a real broker would. */
    private void givenConfirmationAfter(Duration delay) {
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> correlationData.getFuture()
                            .complete(new CorrelationData.Confirm(true, null)));
            return null;
        }).when(rabbitTemplate).send(eq(EXCHANGE), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @Test
    void publish_sendsToTheExchangeWithTheRoutingKeyOfItsTaskType() {
        BackgroundTaskMessage published = task(TaskType.WELCOME_EMAIL);
        givenConfirmation(true, null);

        publisher.publish(published);

        ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<CorrelationData> correlationData = ArgumentCaptor.forClass(CorrelationData.class);
        verify(rabbitTemplate).send(eq(EXCHANGE), eq("task.welcome-email"), message.capture(),
                correlationData.capture());

        assertThat(correlationData.getValue().getId()).isEqualTo(published.taskId());
        assertThat(message.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getValue().getBody()).isEqualTo(serialised(published));
    }

    @Test
    void publish_routesEveryTaskTypeWithItsOwnRoutingKey() {
        for (TaskType type : TaskType.values()) {
            BackgroundTaskMessage published = task(type);
            givenConfirmation(true, null);

            publisher.publish(published);

            verify(rabbitTemplate).send(eq(EXCHANGE), eq(TaskQueue.of(type).routingKey()),
                    any(Message.class), any(CorrelationData.class));
            reset(rabbitTemplate);
        }
    }

    @Test
    void publish_waitsUntilTheBrokerConfirms() {
        BackgroundTaskMessage published = task(TaskType.STATEMENT_GENERATION);
        givenConfirmationAfter(Duration.ofMillis(500));

        long startedAt = System.nanoTime();
        publisher.publish(published);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertThat(elapsedMillis)
                .as("publish returns only once the confirm future completed")
                .isGreaterThanOrEqualTo(400L);
    }

    @Test
    void publish_nack_failsWithTheTaskIdAndTheReason() {
        BackgroundTaskMessage published = task(TaskType.DOCUMENT_SCAN);
        givenConfirmation(false, "no queue is bound to that routing key");

        assertThatThrownBy(() -> publisher.publish(published))
                .isInstanceOfSatisfying(TaskPublishException.class, failure -> {
                    assertThat(failure.taskId()).isEqualTo(published.taskId());
                    assertThat(failure.reason()).contains("no queue is bound to that routing key");
                });
    }

    @Test
    void publish_returnedAsUnroutable_failsWithTheTaskIdAndTheReturn() {
        BackgroundTaskMessage published = task(TaskType.DOCUMENT_EXPORT);
        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(new ReturnedMessage(new Message(new byte[0]), 312, "NO_ROUTE", EXCHANGE,
                    TaskQueue.of(published.type()).routingKey()));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(eq(EXCHANGE), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publish(published))
                .isInstanceOfSatisfying(TaskPublishException.class, failure -> {
                    assertThat(failure.taskId()).isEqualTo(published.taskId());
                    assertThat(failure.reason()).contains("unroutable").contains("NO_ROUTE");
                });
    }

    @Test
    void publish_withoutAConfirmationWithinTheTimeout_failsWithTheTaskId() {
        publisher = new RabbitBackgroundTaskPublisher(rabbitTemplate, properties(Duration.ofMillis(200)),
                objectMapper);
        BackgroundTaskMessage published = task(TaskType.WELCOME_EMAIL);
        doAnswer(invocation -> null)
                .when(rabbitTemplate).send(eq(EXCHANGE), anyString(), any(Message.class), any(CorrelationData.class));

        assertThatThrownBy(() -> publisher.publish(published))
                .isInstanceOf(TaskPublishException.class)
                .hasMessageContaining(published.taskId())
                .hasMessageContaining("did not confirm");
    }

    @Test
    void publish_confirmedByTheBroker_returnsNormally() {
        BackgroundTaskMessage published = task(TaskType.STATEMENT_GENERATION);
        givenConfirmation(true, null);

        assertThatCode(() -> publisher.publish(published)).doesNotThrowAnyException();
    }

    private byte[] serialised(BackgroundTaskMessage task) {
        try {
            return objectMapper.writeValueAsBytes(task);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
