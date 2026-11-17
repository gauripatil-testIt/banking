package com.techverito.banking.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.entity.BackgroundTaskType;
import com.techverito.banking.event.BusinessEventType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Proves the task-queue topology against a real broker: the exchange and the four queues
 * are durable, every queue is bound to exactly its own routing key, and a message routed
 * to one task type lands on that task type's queue and nowhere else.
 *
 * <p>The test needs Docker and nothing else - the container's own endpoint and credentials
 * are used, with no real RabbitMQ deployment and no committed secret. Like
 * {@code S3DocumentStorageLocalStackTest} it is skipped rather than failed when Docker is
 * unavailable, so the rest of the suite still runs.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class RabbitTaskTopologyIntegrationTest {

    private static final String STATEMENT_ROUTING_KEY = "task.statement-generation";

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    private static CachingConnectionFactory connectionFactory;

    private static RabbitAdmin admin;

    private static RabbitTemplate template;

    @BeforeAll
    static void declareTopology() {
        connectionFactory = new CachingConnectionFactory();
        connectionFactory.setHost(RABBIT.getHost());
        connectionFactory.setPort(RABBIT.getAmqpPort());
        connectionFactory.setUsername(RABBIT.getAdminUsername());
        connectionFactory.setPassword(RABBIT.getAdminPassword());
        admin = new RabbitAdmin(connectionFactory);
        template = new RabbitTemplate(connectionFactory);

        declare(new RabbitTaskConfig().taskTopology(PROPERTIES));
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
    void eachTaskTypeHasItsOwnDurableQueueBoundToItsOwnRoutingKey() {
        JsonNode exchange = findByName(management("/api/exchanges"), PROPERTIES.exchange());
        assertThat(exchange.path("type").asText()).isEqualTo("topic");
        assertThat(exchange.path("durable").asBoolean()).as("the exchange must be durable").isTrue();

        JsonNode queues = management("/api/queues");
        for (BackgroundTaskType taskType : BackgroundTaskType.values()) {
            JsonNode queue = findByName(queues, queueName(taskType));
            assertThat(queue.path("durable").asBoolean())
                    .as("%s must be durable so a queued task survives a broker restart", queueName(taskType))
                    .isTrue();
            assertThat(queue.path("auto_delete").asBoolean()).isFalse();

            List<String> bindingKeys = new ArrayList<>();
            for (JsonNode binding : queue.path("bindings")) {
                assertThat(binding.path("source").asText()).isEqualTo(PROPERTIES.exchange());
                bindingKeys.add(binding.path("routing_key").asText());
            }
            assertThat(bindingKeys)
                    .as("%s must be bound to exactly its own routing key", queueName(taskType))
                    .containsExactly(PROPERTIES.routingKey(taskType));
        }
    }

    @Test
    void aMessageReachesOnlyTheQueueOfItsOwnRoutingKey() {
        template.convertAndSend(PROPERTIES.exchange(), STATEMENT_ROUTING_KEY, "{\"eventId\":\"topology-test\"}");

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
                messageCount(BackgroundTaskType.STATEMENT_GENERATION)).isEqualTo(1));

        for (BackgroundTaskType other : BackgroundTaskType.values()) {
            if (other != BackgroundTaskType.STATEMENT_GENERATION) {
                assertThat(messageCount(other))
                        .as("%s is a different task type and must not receive the message", queueName(other))
                        .isZero();
            }
        }
    }

    @Test
    void theFourTaskTypesUseDistinctRoutingKeysAndTheirOwnEventTypes() {
        assertThat(BackgroundTaskType.values()).hasSize(4);

        assertThat(Arrays.stream(BackgroundTaskType.values()).map(PROPERTIES::routingKey).toList())
                .containsExactlyInAnyOrder("task.welcome-email", "task.statement-generation",
                        "task.document-export", "task.document-scan");

        assertThat(BackgroundTaskType.WELCOME_EMAIL.eventType())
                .isEqualTo(BusinessEventType.WELCOME_EMAIL_REQUESTED);
        assertThat(BackgroundTaskType.STATEMENT_GENERATION.eventType())
                .isEqualTo(BusinessEventType.STATEMENT_REQUESTED);
        assertThat(BackgroundTaskType.DOCUMENT_EXPORT.eventType())
                .isEqualTo(BusinessEventType.EXPORT_REQUESTED);
        assertThat(BackgroundTaskType.DOCUMENT_SCAN.eventType())
                .isEqualTo(BusinessEventType.DOCUMENT_SCAN_REQUESTED);
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

    private static JsonNode findByName(JsonNode collection, String name) {
        Optional<JsonNode> found = StreamSupport.stream(collection.spliterator(), false)
                .filter(node -> name.equals(node.path("name").asText()))
                .findFirst();
        assertThat(found).as("%s must exist on the broker", name).isPresent();
        return found.orElseThrow();
    }

    private static JsonNode management(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(RABBIT.getHttpUrl() + path))
                    .header("Authorization", basicAuthorization())
                    .GET()
                    .build();
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("management API %s", path).isEqualTo(200);
            return MAPPER.readTree(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the broker management API at " + path, ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not call the broker management API at " + path, ex);
        }
    }

    private static String basicAuthorization() {
        String credentials = RABBIT.getAdminUsername() + ":" + RABBIT.getAdminPassword();
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}
