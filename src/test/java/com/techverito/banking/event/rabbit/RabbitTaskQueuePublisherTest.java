package com.techverito.banking.event.rabbit;

import com.techverito.banking.config.RabbitTaskConfig;
import com.techverito.banking.config.TaskQueueProperties;
import com.techverito.banking.entity.BackgroundTaskType;
import com.techverito.banking.exception.TaskPublishException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * Proves the confirm contract of the RabbitMQ task adapter: a normal return means the
 * broker owns the message, and every other outcome - a nack, an unroutable message, an
 * unreachable broker - is a {@link TaskPublishException} with nothing left behind.
 *
 * <p>The positive, unroutable and message-property cases run against a real broker in a
 * Testcontainers container, so no RabbitMQ installation is needed; the class is skipped
 * rather than failed when Docker is unavailable. A nack cannot be provoked on a healthy
 * real broker, so that one case drives the adapter with a mocked template - still without
 * any fallback publisher in the application.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class RabbitTaskQueuePublisherTest {

    private static final TaskQueueProperties PROPERTIES = new TaskQueueProperties(
            "banking.tasks",
            "banking.tasks.welcome-email",
            "banking.tasks.statement-generation",
            "banking.tasks.document-export",
            "banking.tasks.document-scan",
            "task.welcome-email",
            "task.statement-generation",
            "task.document-export",
            "task.document-scan",
            Duration.ofSeconds(2),
            true);

    private static final String EVENT_ID = "3f1d2c9a-8b7e-4c6d-9f01-2a3b4c5d6e7f";

    private static final String CORRELATION_ID = "corr-2025-03-04-0001";

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    private static CachingConnectionFactory connectionFactory;

    private static RabbitAdmin admin;

    private static RabbitTemplate template;

    private static RabbitTaskQueuePublisher publisher;

    @BeforeAll
    static void setUpBroker() {
        connectionFactory = new CachingConnectionFactory();
        connectionFactory.setHost(RABBIT.getHost());
        connectionFactory.setPort(RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);

        admin = new RabbitAdmin(connectionFactory);
        template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);

        declare(new RabbitTaskConfig().taskTopology(PROPERTIES));
        publisher = new RabbitTaskQueuePublisher(template, PROPERTIES);
    }

    @AfterAll
    static void closeConnection() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void emptyTheQueues() {
        for (BackgroundTaskType taskType : BackgroundTaskType.values()) {
            admin.purgeQueue(queueName(taskType));
        }
    }

    @Test
    void publish_returnsOnlyAfterTheBrokerConfirmedTheMessageOnItsOwnQueue() {
        publisher.publish(PROPERTIES.routingKey(BackgroundTaskType.DOCUMENT_SCAN), messageJson());

        assertThat(messageCount(BackgroundTaskType.DOCUMENT_SCAN))
                .as("publish must not return before the message is on its queue")
                .isEqualTo(1);
        for (BackgroundTaskType other : BackgroundTaskType.values()) {
            if (other != BackgroundTaskType.DOCUMENT_SCAN) {
                assertThat(messageCount(other))
                        .as("%s is a different task type and must not receive the message", queueName(other))
                        .isZero();
            }
        }
    }

    @Test
    void publishedMessageCarriesTheEventIdCorrelationIdAndIsPersistentJson() {
        String json = messageJson();

        publisher.publish(PROPERTIES.routingKey(BackgroundTaskType.WELCOME_EMAIL), json);

        Message received = template.receive(PROPERTIES.welcomeEmailQueue(), 5_000);
        assertThat(received).as("the confirmed message must be on the queue").isNotNull();
        MessageProperties messageProperties = received.getMessageProperties();
        assertThat(messageProperties.getMessageId()).isEqualTo(EVENT_ID);
        assertThat(messageProperties.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(messageProperties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(messageProperties.getContentEncoding()).isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(messageProperties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo(json);
    }

    @Test
    void publish_unroutableRoutingKey_throwsAndLeavesEveryQueueEmpty() {
        assertThatThrownBy(() -> publisher.publish("task.no-such-task", messageJson()))
                .isInstanceOf(TaskPublishException.class)
                .hasMessageContaining("task.no-such-task")
                .hasMessageContaining(EVENT_ID);

        assertNoMessageOnAnyQueue();
    }

    @Test
    void publish_brokerNack_throwsTaskPublishException() {
        RabbitTemplate nackingTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.getFuture().complete(new CorrelationData.Confirm(false, "rejected by the broker"));
            return null;
        }).when(nackingTemplate).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        RabbitTaskQueuePublisher nackingPublisher = new RabbitTaskQueuePublisher(nackingTemplate, PROPERTIES);

        assertThatThrownBy(() -> nackingPublisher.publish(
                PROPERTIES.routingKey(BackgroundTaskType.DOCUMENT_EXPORT), messageJson()))
                .isInstanceOf(TaskPublishException.class)
                .hasMessageContaining("nack")
                .hasMessageContaining("task.document-export")
                .hasMessageContaining(EVENT_ID);

        assertNoMessageOnAnyQueue();
    }

    @Test
    void publish_messageReturnedAsUnroutable_throwsTaskPublishException() {
        RabbitTemplate returningTemplate = mock(RabbitTemplate.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(4);
            correlation.setReturned(new ReturnedMessage(MessageBuilder.withBody(new byte[0]).build(),
                    312, "NO_ROUTE", PROPERTIES.exchange(), "task.statement-generation"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(returningTemplate).convertAndSend(anyString(), anyString(), any(),
                any(MessagePostProcessor.class), any(CorrelationData.class));

        RabbitTaskQueuePublisher returningPublisher = new RabbitTaskQueuePublisher(returningTemplate, PROPERTIES);

        assertThatThrownBy(() -> returningPublisher.publish(
                PROPERTIES.routingKey(BackgroundTaskType.STATEMENT_GENERATION), messageJson()))
                .isInstanceOf(TaskPublishException.class)
                .hasMessageContaining("not routed")
                .hasMessageContaining("task.statement-generation");

        assertNoMessageOnAnyQueue();
    }

    @Test
    void publish_brokerUnreachable_retriesTheConfiguredBoundedNumberOfTimesThenThrows() throws IOException {
        CachingConnectionFactory unreachable = new CachingConnectionFactory();
        unreachable.setHost("localhost");
        unreachable.setPort(closedPort());
        unreachable.setConnectionTimeout(1_000);
        RabbitTemplate unreachableTemplate = new RabbitTemplate(unreachable);
        unreachableTemplate.setMandatory(true);
        RabbitTaskQueuePublisher unreachablePublisher = new RabbitTaskQueuePublisher(unreachableTemplate, PROPERTIES);

        long startedAt = System.nanoTime();
        assertThatThrownBy(() -> unreachablePublisher.publish(
                PROPERTIES.routingKey(BackgroundTaskType.WELCOME_EMAIL), messageJson()))
                .isInstanceOf(TaskPublishException.class)
                .hasMessageContaining("task.welcome-email")
                .hasMessageContaining("retried by the relay");
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
        unreachable.destroy();

        assertThat(elapsedMillis)
                .as("the adapter must back off between its bounded retries before giving up")
                .isGreaterThanOrEqualTo(400L);
    }

    private static String messageJson() {
        return """
                {"eventId":"%s","type":"document.scan.requested","version":1,"occurredAt":"2025-03-04T09:07:45Z",\
                "subjectType":"CUSTOMER","subjectId":1,"causedBy":"ops.user","correlationId":"%s",\
                "payload":{"documentId":1,"customerId":1,"originalFileName":"sample-valid.pdf"}}\
                """.formatted(EVENT_ID, CORRELATION_ID);
    }

    /** Declares the topology the way Spring AMQP would on first connect: exchange, queues, then bindings. */
    private static void declare(Declarables topology) {
        List<Declarable> declarables = new ArrayList<>(topology.getDeclarables());
        for (Declarable declarable : declarables) {
            if (declarable instanceof Exchange exchange) {
                admin.declareExchange(exchange);
            }
        }
        for (Declarable declarable : declarables) {
            if (declarable instanceof Queue queue) {
                admin.declareQueue(queue);
            }
        }
        for (Declarable declarable : declarables) {
            if (declarable instanceof Binding binding) {
                admin.declareBinding(binding);
            }
        }
    }

    private static String queueName(BackgroundTaskType taskType) {
        return switch (taskType) {
            case WELCOME_EMAIL -> PROPERTIES.welcomeEmailQueue();
            case STATEMENT_GENERATION -> PROPERTIES.statementGenerationQueue();
            case DOCUMENT_EXPORT -> PROPERTIES.documentExportQueue();
            case DOCUMENT_SCAN -> PROPERTIES.documentScanQueue();
        };
    }

    private static int messageCount(BackgroundTaskType taskType) {
        return Objects.requireNonNull(admin.getQueueInfo(queueName(taskType)),
                () -> queueName(taskType) + " must exist on the broker").getMessageCount();
    }

    private static void assertNoMessageOnAnyQueue() {
        for (BackgroundTaskType taskType : BackgroundTaskType.values()) {
            assertThat(messageCount(taskType))
                    .as("%s must stay empty when the message was not confirmed", queueName(taskType))
                    .isZero();
        }
    }

    /** A port nothing listens on, so the connection is refused the way it is during an outage. */
    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
