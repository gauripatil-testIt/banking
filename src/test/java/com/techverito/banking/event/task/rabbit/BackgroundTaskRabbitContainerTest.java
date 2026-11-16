package com.techverito.banking.event.task.rabbit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.dockerjava.api.DockerClient;
import com.techverito.banking.dto.AccountRequest;
import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.entity.BackgroundTaskOutboxEntry;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.event.task.BackgroundTaskMessage;
import com.techverito.banking.event.task.BackgroundTaskOutboxRecorder;
import com.techverito.banking.event.task.TaskQueue;
import com.techverito.banking.event.task.TaskType;
import com.techverito.banking.repository.BackgroundTaskOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of BANK-8 against a real broker started by Testcontainers: the real
 * business endpoints, the real background-task outbox, the real scheduled drain and the
 * real RabbitMQ client – there is no stubbed publishing path anywhere in this test.
 *
 * <p>What it shows: a task submitted by a business action reaches its own queue, and only
 * that queue, carrying the envelope the convention requires; submitting the same task
 * twice queues it once; a message already in its durable queue survives a broker restart;
 * and an action performed while the broker is stopped still succeeds, with the task
 * published once the broker is back.</p>
 *
 * <p>It needs Docker and nothing else – no external broker, no credentials and no network
 * beyond the container – and is skipped when Docker is unavailable, exactly as
 * {@link com.techverito.banking.event.outbox.OutboxEndToEndKafkaContainerTest} is.</p>
 *
 * <p>The broker is stopped by killing its container and started again by starting that
 * same container, so the address the application was given at startup stays valid and the
 * durable queues, which live in that container's own filesystem, survive the round trip.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The Kafka business events of BANK-7 are not part of this test; the in-memory
        // transport keeps every business-event row publishing without a second broker.
        "banking.events.transport=in-memory",
        // Retry often and generously, so a short outage never exhausts the attempts of a
        // task this test expects to see published once the broker is back.
        "banking.tasks.max-attempts=100",
        "banking.tasks.retry-delay=PT1S",
        "banking.tasks.drain-interval=PT0.5S",
        "banking.tasks.publish-timeout=PT5S",
        // A dead broker must fail an attempt quickly instead of holding the drain for a
        // minute, so the outage below is observable within the test's patience.
        "spring.rabbitmq.connection-timeout=PT3S",
        // Documents are not part of this test; the local backend needs no AWS.
        "banking.documents.storage=local",
        "banking.documents.storage-dir=./target/test-documents/background-task-rabbit",
        // An in-memory database of its own, generated from the entity mappings.
        "spring.datasource.url=jdbc:h2:mem:background-task-rabbit;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
class BackgroundTaskRabbitContainerTest {

    /** How long a task may take to reach its queue, or a row to become PUBLISHED. */
    private static final long TIMEOUT_SECONDS = 180L;

    /** How long the broker may take to stop listening, or to listen again. */
    private static final long BROKER_TIMEOUT_SECONDS = 180L;

    /** How long a queue is watched, so a duplicate submission would show up as a second message. */
    private static final long SETTLE_SECONDS = 2L;

    /** How long one non-destructive read of a queue waits for a message. */
    private static final long RECEIVE_TIMEOUT_MILLIS = 200L;

    private static final String ACTOR = "ops.user";

    @Container
    static final RabbitMQContainer BROKER =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3-management"))
                    .withStartupTimeout(Duration.ofMinutes(3));

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    BackgroundTaskOutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /**
     * The broker's address, so the application talks to the container this test started.
     * The container is started here as well when the Testcontainers extension has not done
     * it yet, so the address is known whenever the context is created.
     */
    @DynamicPropertySource
    static void rabbitmqProperties(DynamicPropertyRegistry registry) {
        if (!BROKER.isRunning()) {
            BROKER.start();
        }
        registry.add("spring.rabbitmq.host", BROKER::getHost);
        registry.add("spring.rabbitmq.port", BROKER::getAmqpPort);
        registry.add("spring.rabbitmq.username", BROKER::getAdminUsername);
        registry.add("spring.rabbitmq.password", BROKER::getAdminPassword);
    }

    /** Whatever a test did to the broker, the next one finds it running. */
    @AfterEach
    void leaveTheBrokerRunning() throws InterruptedException {
        startBroker();
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** Registers a customer through the real endpoint and answers its body. */
    private JsonNode registerCustomer(String correlationId) {
        CustomerRequest request = new CustomerRequest("Bank", "Eight",
                "bank-8-" + UUID.randomUUID() + "@example.com", "1234567890", CustomerStatus.ACTIVE);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Actor", ACTOR);
        headers.set("X-Correlation-Id", correlationId);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/customers",
                new HttpEntity<>(request, headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CREATED.value());
        return Objects.requireNonNull(response.getBody());
    }

    /** Opens an account through the real endpoint and answers its id. */
    private long openAccount(long customerId) {
        AccountRequest request = new AccountRequest(customerId, "ACC-" + shortId(), AccountType.SAVINGS,
                new BigDecimal("1000.00"), AccountStatus.ACTIVE);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/accounts",
                new HttpEntity<>(request, headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.CREATED.value());
        return Objects.requireNonNull(response.getBody()).get("id").asLong();
    }

    /** Asks for a statement through the real endpoint and answers the queued task's id. */
    private String requestStatement(long accountId, String correlationId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Actor", ACTOR);
        headers.set("X-Correlation-Id", correlationId);
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/accounts/{id}/statements",
                new HttpEntity<>(headers), JsonNode.class, accountId);
        assertThat(response.getStatusCode().value()).isEqualTo(HttpStatus.ACCEPTED.value());
        JsonNode body = Objects.requireNonNull(response.getBody());
        assertThat(body.get("taskType").asText()).isEqualTo(TaskType.STATEMENT_GENERATION.name());
        assertThat(body.get("accountId").asLong()).isEqualTo(accountId);
        return body.get("taskId").asText();
    }

    /** The stored outbox rows of one task, oldest first. */
    private List<BackgroundTaskOutboxEntry> rowsOfTask(String taskId) {
        return outboxRepository.findAll().stream()
                .filter(row -> taskId.equals(row.getTaskId()))
                .toList();
    }

    /** Waits until the drain marked the stored task {@code PUBLISHED}, which under the real adapter means the broker confirmed it. */
    private BackgroundTaskOutboxEntry awaitPublished(String taskId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        BackgroundTaskOutboxEntry row = null;
        while (System.nanoTime() < deadline) {
            row = rowsOfTask(taskId).stream().findFirst().orElse(null);
            if (row != null && row.getStatus() == BackgroundTaskStatus.PUBLISHED) {
                return row;
            }
            Thread.sleep(200);
        }
        assertThat(row).as("the outbox row of %s", taskId).isNotNull();
        assertThat(row.getStatus())
                .as("the outbox row of %s once the broker is back; attempts=%s, lastError=%s",
                        taskId, row.getAttempts(), row.getLastError())
                .isEqualTo(BackgroundTaskStatus.PUBLISHED);
        return row;
    }

    /** Waits until the drain has tried to publish the stored task and recorded the failure. */
    private BackgroundTaskOutboxEntry awaitAttempted(String taskId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Optional<BackgroundTaskOutboxEntry> row = rowsOfTask(taskId).stream().findFirst();
            if (row.isPresent() && row.get().getAttempts() >= 1) {
                return row.get();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No background task row " + taskId + " was recorded and attempted");
    }

    /**
     * Reads one message from the queue of its task type, exactly as a worker would, and
     * deserialises it into the message envelope.
     */
    private Optional<BackgroundTaskMessage> readsFromTaskQueue(TaskQueue queue) {
        Message message = rabbitTemplate.receive(queue.queueName(), RECEIVE_TIMEOUT_MILLIS);
        if (message == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(message.getBody(), BackgroundTaskMessage.class));
        } catch (IOException ex) {
            throw new IllegalStateException("A message in " + queue.queueName() + " is not a background task", ex);
        }
    }

    /** Waits for one task to appear in its queue and answers the message as the broker delivered it. */
    private BackgroundTaskMessage awaitMessage(TaskQueue queue, String taskId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            Optional<BackgroundTaskMessage> received = readsFromTaskQueue(queue);
            if (received.isPresent() && taskId.equals(received.get().taskId())) {
                return received.get();
            }
            if (received.isEmpty()) {
                Thread.sleep(200);
            }
        }
        throw new AssertionError("The task " + taskId + " never reached " + queue.queueName());
    }

    /**
     * Every message of one task that is in a queue, read over a settle window so that a
     * second copy of the same task – the work queued twice – would be read as well.
     */
    private List<BackgroundTaskMessage> messagesWith(String taskId, TaskQueue queue) throws InterruptedException {
        List<BackgroundTaskMessage> found = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_SECONDS);
        while (System.nanoTime() < deadline) {
            Optional<BackgroundTaskMessage> received = readsFromTaskQueue(queue);
            if (received.isPresent()) {
                if (taskId.equals(received.get().taskId())) {
                    found.add(received.get());
                }
            } else {
                Thread.sleep(200);
            }
        }
        return found;
    }

    /** Waits until the queue holds a message, without consuming it, so a restart can be shown to keep it. */
    private void awaitQueueNotEmpty(TaskQueue queue) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            try {
                if (amqpAdmin.getQueueInfo(queue.queueName()).getMessageCount() >= 1) {
                    return;
                }
            } catch (AmqpException brokerNotReachableYet) {
                // Nothing can be read while the broker is down or still starting; try again.
            }
            Thread.sleep(200);
        }
        throw new AssertionError("The queue " + queue.queueName() + " stayed empty");
    }

    private static DockerClient dockerClient() {
        return DockerClientFactory.instance().client();
    }

    /**
     * Stops the broker by killing its container, and waits until nothing listens on the
     * advertised address any more, so an action taken next really is taken while RabbitMQ
     * is down.
     */
    private static void stopBroker() throws InterruptedException {
        dockerClient().killContainerCmd(BROKER.getContainerId()).exec();
        awaitBrokerReachable(false);
    }

    /**
     * Starts the broker container again. It is a no-op when the broker is already
     * listening, so it is safe to call from an {@code @AfterEach} method as well.
     */
    private static void startBroker() throws InterruptedException {
        if (!isBrokerReachable()) {
            dockerClient().startContainerCmd(BROKER.getContainerId()).exec();
        }
        awaitBrokerReachable(true);
    }

    private static void awaitBrokerReachable(boolean reachable) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BROKER_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (isBrokerReachable() == reachable) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(isBrokerReachable())
                .as("the broker at %s:%s should be %s", BROKER.getHost(), BROKER.getAmqpPort(),
                        reachable ? "reachable" : "unreachable")
                .isEqualTo(reachable);
    }

    /** Whether the broker's advertised address accepts a TCP connection. */
    private static boolean isBrokerReachable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(BROKER.getHost(), BROKER.getAmqpPort()), 500);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    @Test
    void welcomeEmailTask_reachesItsOwnQueueAndNoOther() throws Exception {
        JsonNode customer = registerCustomer("corr-bank-8-rabbit-customer");
        long customerId = customer.get("id").asLong();
        String taskId = BackgroundTaskOutboxRecorder.taskIdFor(TaskType.WELCOME_EMAIL, "customer", customerId);

        BackgroundTaskOutboxEntry row = awaitPublished(taskId);
        assertThat(row.getPublishedAt()).isNotNull();

        BackgroundTaskMessage message = awaitMessage(TaskQueue.WELCOME_EMAIL_QUEUE, taskId);
        assertThat(message.taskId()).isEqualTo(taskId);
        assertThat(message.type()).isEqualTo(TaskType.WELCOME_EMAIL);
        assertThat(message.recordType()).isEqualTo("customer");
        assertThat(message.recordId()).isEqualTo(customerId);
        assertThat(message.customerId()).isEqualTo(customerId);
        assertThat(message.requestedBy()).isEqualTo(ACTOR);
        assertThat(message.correlationId()).isEqualTo("corr-bank-8-rabbit-customer");
        assertThat(message.occurredAt()).isNotNull();

        // One queue per task type: the welcome email is in its own queue and nowhere else.
        for (TaskQueue other : List.of(TaskQueue.STATEMENT_GENERATION_QUEUE, TaskQueue.DOCUMENT_EXPORT_QUEUE,
                TaskQueue.DOCUMENT_SCAN_QUEUE)) {
            assertThat(messagesWith(taskId, other))
                    .as("the welcome email in %s", other.queueName())
                    .isEmpty();
        }
    }

    @Test
    void theSameStatementRequestTwice_leavesOneRowAndOneMessage() throws Exception {
        long customerId = registerCustomer("corr-bank-8-rabbit-duplicate").get("id").asLong();
        long accountId = openAccount(customerId);

        String firstTaskId = requestStatement(accountId, "corr-bank-8-rabbit-statement");
        String secondTaskId = requestStatement(accountId, "corr-bank-8-rabbit-statement-again");

        assertThat(secondTaskId).isEqualTo(firstTaskId);
        awaitPublished(firstTaskId);

        List<BackgroundTaskMessage> queued = messagesWith(firstTaskId, TaskQueue.STATEMENT_GENERATION_QUEUE);
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).type()).isEqualTo(TaskType.STATEMENT_GENERATION);
        assertThat(queued.get(0).recordType()).isEqualTo("account");
        assertThat(queued.get(0).recordId()).isEqualTo(accountId);

        assertThat(rowsOfTask(firstTaskId)).hasSize(1);
    }

    @Test
    void aMessageAlreadyInItsDurableQueue_survivesABrokerRestart() throws Exception {
        long customerId = registerCustomer("corr-bank-8-rabbit-restart").get("id").asLong();
        String taskId = BackgroundTaskOutboxRecorder.taskIdFor(TaskType.WELCOME_EMAIL, "customer", customerId);

        awaitPublished(taskId);
        awaitQueueNotEmpty(TaskQueue.WELCOME_EMAIL_QUEUE);

        stopBroker();
        startBroker();

        // Nothing consumed it, the queue is durable and the message is persistent, so the
        // same task is still there – and still readable – after the broker came back.
        awaitQueueNotEmpty(TaskQueue.WELCOME_EMAIL_QUEUE);
        BackgroundTaskMessage message = awaitMessage(TaskQueue.WELCOME_EMAIL_QUEUE, taskId);
        assertThat(message.taskId()).isEqualTo(taskId);
        assertThat(message.type()).isEqualTo(TaskType.WELCOME_EMAIL);
        assertThat(message.recordType()).isEqualTo("customer");
        assertThat(message.recordId()).isEqualTo(customerId);
        assertThat(message.requestedBy()).isEqualTo(ACTOR);
    }

    @Test
    void taskSubmittedWhileTheBrokerIsDown_isQueuedWhenItReturns() throws Exception {
        stopBroker();
        try {
            // Criterion 7: the customer action is committed without waiting for RabbitMQ.
            long customerId = registerCustomer("corr-bank-8-rabbit-outage").get("id").asLong();
            String taskId = BackgroundTaskOutboxRecorder.taskIdFor(TaskType.WELCOME_EMAIL, "customer", customerId);

            BackgroundTaskOutboxEntry pending = awaitAttempted(taskId);
            assertThat(pending.getStatus()).isEqualTo(BackgroundTaskStatus.PENDING);
            assertThat(pending.getAttempts()).isGreaterThanOrEqualTo(1);
            assertThat(pending.getLastError()).isNotBlank();
            assertThat(pending.getPublishedAt()).isNull();
            assertThat(isBrokerReachable()).as("the broker is stopped while the action is served").isFalse();

            startBroker();

            BackgroundTaskOutboxEntry published = awaitPublished(taskId);
            assertThat(published.getPublishedAt()).isNotNull();
            assertThat(isBrokerReachable()).isTrue();

            BackgroundTaskMessage message = awaitMessage(TaskQueue.WELCOME_EMAIL_QUEUE, taskId);
            assertThat(message.type()).isEqualTo(TaskType.WELCOME_EMAIL);
            assertThat(message.recordId()).isEqualTo(customerId);
            assertThat(message.requestedBy()).isEqualTo(ACTOR);
            assertThat(message.correlationId()).isEqualTo("corr-bank-8-rabbit-outage");
        } finally {
            // Whatever happened above, the broker is left running for the rest of the suite.
            startBroker();
        }
    }
}
