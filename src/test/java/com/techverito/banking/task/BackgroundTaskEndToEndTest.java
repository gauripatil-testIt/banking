package com.techverito.banking.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.entity.BackgroundTask;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.entity.BackgroundTaskType;
import com.techverito.banking.repository.BackgroundTaskRepository;
import com.techverito.banking.service.TaskOutboxRelay;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * BANK-8 end to end: the real HTTP endpoints, the real outbox table and the real RabbitMQ client
 * against a broker in a Testcontainers container. Nothing in the application is stubbed - the only
 * double in this class is the container itself.
 *
 * <p>It proves the five things the story promises: a submission reaches its own queue with the
 * documented envelope and no other queue; a message that cannot be confirmed leaves the task
 * PENDING and is published once the queue is back; the same business key submitted twice yields one
 * task id and one message; a message already queued survives a broker restart and a task written
 * during the outage is published when the broker returns; and a broker outage never fails the
 * caller.</p>
 *
 * <p>Like {@code S3DocumentStorageLocalStackTest} it needs Docker and nothing else - the container's
 * own endpoint and credentials are used, no real RabbitMQ deployment and no committed secret - and
 * it is skipped rather than failed when Docker is unavailable, so the rest of the suite still
 * runs.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class BackgroundTaskEndToEndTest {

    private static final String WELCOME_EMAIL_QUEUE = "banking.tasks.welcome-email";

    private static final String STATEMENT_QUEUE = "banking.tasks.statement-generation";

    private static final String EXPORT_QUEUE = "banking.tasks.document-export";

    private static final String SCAN_QUEUE = "banking.tasks.document-scan";

    private static final List<String> ALL_QUEUES =
            List.of(WELCOME_EMAIL_QUEUE, STATEMENT_QUEUE, EXPORT_QUEUE, SCAN_QUEUE);

    /** How long a message is allowed to take to appear on its queue. */
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(30);

    /** How long the broker may take to come back after a restart. */
    private static final Duration RESTART_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(10);

    private static final byte[] PDF_CONTENT =
            "%PDF-1.4\n% BANK-8 end-to-end sample\n".getBytes(StandardCharsets.UTF_8);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Container
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management"));

    @DynamicPropertySource
    static void brokerAndStorageProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        // Documents stay on the disk of the test JVM, so no S3 bucket is needed here.
        registry.add("banking.documents.storage", () -> "local");
        registry.add("banking.documents.storage-dir", () -> "./target/test-documents/e2e");
        // The test drives the relay itself, so the schedule can never race an assertion.
        registry.add("banking.tasks.relay-interval", () -> "10h");
    }

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    Declarables taskTopology;

    @Autowired
    TaskOutboxRelay relay;

    @Autowired
    BackgroundTaskRepository taskRepository;

    @BeforeEach
    void resetTheQueues() {
        restoreTopology();
        for (String queue : ALL_QUEUES) {
            amqpAdmin.purgeQueue(queue);
        }
    }

    @AfterEach
    void leaveTheBrokerRunning() {
        if (!RABBIT.isRunning()) {
            RABBIT.start();
        }
    }

    @Test
    void everySubmissionReachesItsOwnQueueWithTheDocumentedEnvelope() throws Exception {
        long customerId = createCustomer("e2e.send@example.com");
        long documentId = uploadDocument(customerId, "sample-valid.pdf", "ops.user");
        long accountId = createAccount(customerId, "00080001");
        JsonNode export = requestExport("customers", "ops.user.send");
        JsonNode statement = requestStatement(accountId, "2025-02-01", "2025-02-28", "ops.user.statement");

        await().atMost(PUBLISH_TIMEOUT).untilAsserted(() -> {
            relay.relayPending();
            assertThat(messageCount(WELCOME_EMAIL_QUEUE)).isEqualTo(1);
            assertThat(messageCount(SCAN_QUEUE)).isEqualTo(1);
            assertThat(messageCount(EXPORT_QUEUE)).isEqualTo(1);
            assertThat(messageCount(STATEMENT_QUEUE)).isEqualTo(1);
        });

        JsonNode welcomeEmail = publishedOn(WELCOME_EMAIL_QUEUE);
        assertEnvelope(welcomeEmail, "welcome.email.requested", "CUSTOMER", customerId, "system");
        assertThat(welcomeEmail.at("/payload/customerId").asLong()).isEqualTo(customerId);
        assertThat(welcomeEmail.at("/payload/email").asText()).isEqualTo("e2e.send@example.com");

        JsonNode scan = publishedOn(SCAN_QUEUE);
        assertEnvelope(scan, "document.scan.requested", "CUSTOMER", customerId, "ops.user");
        assertThat(scan.at("/payload/documentId").asLong()).isEqualTo(documentId);
        assertThat(scan.at("/payload/customerId").asLong()).isEqualTo(customerId);
        assertThat(scan.at("/payload/originalFileName").asText()).isEqualTo("sample-valid.pdf");

        JsonNode exportMessage = publishedOn(EXPORT_QUEUE);
        assertEnvelope(exportMessage, "export.requested", "CUSTOMER", 0L, "ops.user.send");
        assertThat(exportMessage.at("/payload/exportType").asText()).isEqualTo("CUSTOMERS");
        assertThat(exportMessage.at("/payload/exportedBy").asText()).isEqualTo("ops.user.send");

        JsonNode statementMessage = publishedOn(STATEMENT_QUEUE);
        assertEnvelope(statementMessage, "statement.requested", "ACCOUNT", accountId, "ops.user.statement");
        assertThat(statementMessage.at("/payload/periodStart").asText()).isEqualTo("2025-02-01");
        assertThat(statementMessage.at("/payload/periodEnd").asText()).isEqualTo("2025-02-28");

        // The two task endpoints answer with the id they queued, and the task became SUBMITTED
        // only once the broker had confirmed the message.
        JsonNode exportStatus = taskStatus(export.get("taskId").asLong());
        assertThat(exportStatus.get("taskType").asText()).isEqualTo("DOCUMENT_EXPORT");
        assertThat(exportStatus.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(exportStatus.get("publishedAt").isNull()).isFalse();

        JsonNode statementStatus = taskStatus(statement.get("taskId").asLong());
        assertThat(statementStatus.get("taskType").asText()).isEqualTo("STATEMENT_GENERATION");
        assertThat(statementStatus.get("status").asText()).isEqualTo("SUBMITTED");

        // One message was taken off each queue, so every queue is empty again: each submission
        // produced exactly one message, on its own queue and nowhere else.
        for (String queue : ALL_QUEUES) {
            assertThat(messageCount(queue))
                    .as("%s must have held exactly the one message that was received", queue)
                    .isZero();
        }
    }

    @Test
    void submittingTheSameTaskTwiceQueuesItOnce() throws Exception {
        JsonNode first = requestExport("accounts", "ops.user.duplicate");
        JsonNode second = requestExport("accounts", "ops.user.duplicate");

        assertThat(second.get("taskId").asLong())
                .as("a repeated submission reports the task that already exists")
                .isEqualTo(first.get("taskId").asLong());
        assertThat(second.get("status").asText()).isEqualTo("PENDING");

        await().atMost(PUBLISH_TIMEOUT).untilAsserted(() -> {
            relay.relayPending();
            assertThat(messageCount(EXPORT_QUEUE)).isEqualTo(1);
        });

        JsonNode message = publishedOn(EXPORT_QUEUE);
        assertEnvelope(message, "export.requested", "CUSTOMER", 0L, "ops.user.duplicate");
        assertThat(message.at("/payload/exportType").asText()).isEqualTo("ACCOUNTS");

        relay.relayPending();

        // The task is already SUBMITTED, so a second run publishes nothing anywhere.
        assertNoMessageOnAnyQueue();
        assertThat(rowCount(BackgroundTaskType.DOCUMENT_EXPORT, "accounts:ops.user.duplicate"))
                .as("submitting the same work twice writes one outbox row")
                .isEqualTo(1);
    }

    @Test
    void aMessageThatCannotBeConfirmedLeavesTheTaskPendingAndIsPublishedOnceTheQueueIsBack() throws Exception {
        String businessKey = "customers:ops.user.unconfirmed";

        // Without its queue the task's routing key is unroutable, which is the one confirmation
        // failure a healthy broker can be made to produce.
        amqpAdmin.deleteQueue(EXPORT_QUEUE);
        try {
            JsonNode accepted = requestExport("customers", "ops.user.unconfirmed");
            assertThat(accepted.get("status").asText())
                    .as("the caller is answered 202 even though the message cannot be confirmed")
                    .isEqualTo("PENDING");
            assertThat(accepted.get("taskType").asText()).isEqualTo("DOCUMENT_EXPORT");

            relay.relayPending();

            BackgroundTask stillQueued = taskRow(BackgroundTaskType.DOCUMENT_EXPORT, businessKey);
            assertThat(stillQueued.getStatus())
                    .as("an unconfirmed message must never count as submitted")
                    .isEqualTo(BackgroundTaskStatus.PENDING);
            assertThat(stillQueued.getPublishedAt()).isNull();
            assertThat(stillQueued.getAttempts()).isPositive();
            assertThat(stillQueued.getNextAttemptAt()).isNotNull();
            assertThat(amqpAdmin.getQueueInfo(EXPORT_QUEUE))
                    .as("the export queue is gone, so the message could not be routed anywhere")
                    .isNull();
            for (String queue : ALL_QUEUES) {
                if (!EXPORT_QUEUE.equals(queue)) {
                    assertThat(messageCount(queue))
                            .as("%s must hold no message from the unconfirmed task", queue)
                            .isZero();
                }
            }
        } finally {
            restoreTopology();
        }

        await().atMost(PUBLISH_TIMEOUT).untilAsserted(() -> {
            relay.relayPending();
            assertThat(messageCount(EXPORT_QUEUE)).isEqualTo(1);
        });

        assertEnvelope(publishedOn(EXPORT_QUEUE), "export.requested", "CUSTOMER", 0L, "ops.user.unconfirmed");
        assertThat(taskRow(BackgroundTaskType.DOCUMENT_EXPORT, businessKey).getStatus())
                .isEqualTo(BackgroundTaskStatus.SUBMITTED);
        assertThat(rowCount(BackgroundTaskType.DOCUMENT_EXPORT, businessKey))
                .as("the retried publish adds no second outbox row")
                .isEqualTo(1);
    }

    @Test
    void aQueuedMessageSurvivesABrokerRestartAndAPendingTaskIsPublishedOnceTheBrokerIsBack() throws Exception {
        requestExport("both", "ops.user.restart");
        awaitQueued(EXPORT_QUEUE, 1);
        assertThat(taskRow(BackgroundTaskType.DOCUMENT_EXPORT, "both:ops.user.restart").getStatus())
                .isEqualTo(BackgroundTaskStatus.SUBMITTED);

        RABBIT.stop();
        try {
            JsonNode queuedWhileDown = requestExport("customers", "ops.user.restart.down");
            assertThat(queuedWhileDown.get("status").asText())
                    .as("the caller is answered while the broker is down")
                    .isEqualTo("PENDING");

            relay.relayPending();
            assertThat(taskRow(BackgroundTaskType.DOCUMENT_EXPORT, "customers:ops.user.restart.down").getStatus())
                    .as("an unreachable broker keeps the task queued instead of losing it")
                    .isEqualTo(BackgroundTaskStatus.PENDING);
        } finally {
            RABBIT.start();
        }

        assertThat(messageCount(EXPORT_QUEUE))
                .as("a durable queue keeps the persistent message across a broker restart")
                .isEqualTo(1);

        await().atMost(RESTART_TIMEOUT).untilAsserted(() -> {
            relay.relayPending();
            assertThat(taskRow(BackgroundTaskType.DOCUMENT_EXPORT, "customers:ops.user.restart.down").getStatus())
                    .isEqualTo(BackgroundTaskStatus.SUBMITTED);
        });

        assertThat(messageCount(EXPORT_QUEUE))
                .as("the task queued during the restart is published exactly once when the broker is back")
                .isEqualTo(2);
        assertThat(rowCount(BackgroundTaskType.DOCUMENT_EXPORT, "customers:ops.user.restart.down"))
                .isEqualTo(1);
    }

    @Test
    void withTheBrokerDownEverySubmissionStillReturnsAndIsQueuedOnceItIsBack() throws Exception {
        RABBIT.stop();
        try {
            long customerId = createCustomer("e2e.outage@example.com");
            long documentId = uploadDocument(customerId, "claim.pdf", "ops.user.outage");
            long accountId = createAccount(customerId, "00080002");
            JsonNode export = requestExport("accounts", "ops.user.outage");
            JsonNode statement = requestStatement(accountId, "2025-03-01", "2025-03-31", "ops.user.outage");

            assertThat(export.get("status").asText()).isEqualTo("PENDING");
            assertThat(statement.get("status").asText()).isEqualTo("PENDING");

            relay.relayPending();

            assertThat(taskRow(BackgroundTaskType.WELCOME_EMAIL, String.valueOf(customerId)).getStatus())
                    .isEqualTo(BackgroundTaskStatus.PENDING);
            assertThat(taskRow(BackgroundTaskType.DOCUMENT_SCAN, String.valueOf(documentId)).getStatus())
                    .isEqualTo(BackgroundTaskStatus.PENDING);
            assertThat(taskRow(BackgroundTaskType.DOCUMENT_EXPORT, "accounts:ops.user.outage").getStatus())
                    .isEqualTo(BackgroundTaskStatus.PENDING);
            assertThat(taskRow(BackgroundTaskType.STATEMENT_GENERATION,
                    statementKey(accountId, "2025-03-01", "2025-03-31")).getStatus())
                    .isEqualTo(BackgroundTaskStatus.PENDING);
        } finally {
            RABBIT.start();
        }

        await().atMost(RESTART_TIMEOUT).untilAsserted(() -> {
            relay.relayPending();
            assertThat(messageCount(WELCOME_EMAIL_QUEUE)).isEqualTo(1);
            assertThat(messageCount(SCAN_QUEUE)).isEqualTo(1);
            assertThat(messageCount(EXPORT_QUEUE)).isEqualTo(1);
            assertThat(messageCount(STATEMENT_QUEUE)).isEqualTo(1);
        });

        // A second relay run after everything was confirmed publishes nothing again.
        relay.relayPending();
        for (String queue : ALL_QUEUES) {
            assertThat(messageCount(queue))
                    .as("%s must hold exactly the one message of its own task", queue)
                    .isEqualTo(1);
        }
    }

    /** Creates a customer over HTTP and returns its id, the business key of its welcome email. */
    private long createCustomer(String email) {
        String body = "{\"firstName\":\"John\",\"lastName\":\"Doe\",\"email\":\"" + email
                + "\",\"phone\":\"1234567890\",\"status\":\"ACTIVE\"}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/customers", jsonEntity(body),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Objects.requireNonNull(response.getBody()).get("id").asLong();
    }

    /** Creates an account for the customer and returns its id. */
    private long createAccount(long customerId, String accountNumber) {
        String body = "{\"customerId\":" + customerId + ",\"accountNumber\":\"" + accountNumber
                + "\",\"type\":\"SAVINGS\",\"balance\":1000.00,\"status\":\"ACTIVE\"}";
        ResponseEntity<JsonNode> response = restTemplate.postForEntity("/accounts", jsonEntity(body),
                JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Objects.requireNonNull(response.getBody()).get("id").asLong();
    }

    /** Uploads a small PDF for the customer and returns the new document's id. */
    private long uploadDocument(long customerId, String fileName, String uploadedBy) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(PDF_CONTENT) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        body.add("documentType", "ID_PROOF");
        body.add("uploadedBy", uploadedBy);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/customers/" + customerId + "/documents", new HttpEntity<>(body, headers), JsonNode.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Objects.requireNonNull(response.getBody()).get("id").asLong();
    }

    /** Submits the CSV export as background work and returns the 202 body with its task id. */
    private JsonNode requestExport(String type, String exportedBy) {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity(
                "/exports?type={type}&exportedBy={exportedBy}", JsonNode.class, type, exportedBy);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return Objects.requireNonNull(response.getBody());
    }

    /** Submits statement generation for one period and returns the 202 body with its task id. */
    private JsonNode requestStatement(long accountId, String periodStart, String periodEnd, String requestedBy) {
        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                "/accounts/{accountId}/statements?periodStart={start}&periodEnd={end}&requestedBy={requestedBy}",
                new HttpEntity<>(new HttpHeaders()), JsonNode.class, accountId, periodStart, periodEnd,
                requestedBy);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return Objects.requireNonNull(response.getBody());
    }

    /** Polls the task endpoint the submission pointed the caller at. */
    private JsonNode taskStatus(long taskId) {
        ResponseEntity<JsonNode> response = restTemplate.getForEntity("/tasks/{taskId}", JsonNode.class, taskId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Objects.requireNonNull(response.getBody());
    }

    private HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    /** The envelope every task message must carry, as documented in the BANK-6 format page. */
    private void assertEnvelope(JsonNode envelope, String wireValue, String subjectType, long subjectId,
                                String causedBy) {
        assertThat(envelope.get("eventId").asText()).isNotBlank();
        assertThat(envelope.get("type").asText()).isEqualTo(wireValue);
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        assertThat(envelope.get("occurredAt").asText()).isNotBlank();
        assertThat(envelope.get("subjectType").asText()).isEqualTo(subjectType);
        assertThat(envelope.get("subjectId").asLong()).isEqualTo(subjectId);
        assertThat(envelope.get("causedBy").asText()).isEqualTo(causedBy);
        assertThat(envelope.get("correlationId").asText()).isNotBlank();
        assertThat(envelope.get("payload").isObject()).isTrue();
    }

    /** Runs the relay until the queue holds the expected number of messages. */
    private void awaitQueued(String queue, int expected) {
        await().atMost(PUBLISH_TIMEOUT).untilAsserted(() -> {
            relay.relayPending();
            assertThat(messageCount(queue)).isEqualTo(expected);
        });
    }

    /** Takes one message off a queue, failing the test when nothing arrived. */
    private JsonNode publishedOn(String queue) throws Exception {
        Message message = rabbitTemplate.receive(queue, RECEIVE_TIMEOUT.toMillis());
        assertThat(message).as("a task message must be on %s", queue).isNotNull();
        return MAPPER.readTree(new String(Objects.requireNonNull(message).getBody(), StandardCharsets.UTF_8));
    }

    private void assertNoMessageOnAnyQueue() {
        for (String queue : ALL_QUEUES) {
            assertThat(messageCount(queue))
                    .as("%s must stay empty when no message was published", queue)
                    .isZero();
        }
    }

    private int messageCount(String queue) {
        QueueInformation information = amqpAdmin.getQueueInfo(queue);
        return Objects.requireNonNull(information, () -> queue + " must exist on the broker").getMessageCount();
    }

    private BackgroundTask taskRow(BackgroundTaskType taskType, String businessKey) {
        return taskRepository.findByTaskTypeAndBusinessKey(taskType, businessKey)
                .orElseThrow(() -> new AssertionError(
                        "No " + taskType + " task was written for business key " + businessKey));
    }

    private long rowCount(BackgroundTaskType taskType, String businessKey) {
        return taskRepository.findAll().stream()
                .filter(task -> task.getTaskType() == taskType && businessKey.equals(task.getBusinessKey()))
                .count();
    }

    /** The business key of a statement request, in the shape TaskSubmissionService builds it. */
    private String statementKey(long accountId, String periodStart, String periodEnd) {
        return accountId + ":" + periodStart + ":" + periodEnd;
    }

    /** Declares the topology the way Spring AMQP does on first connect: exchange, queues, bindings. */
    private void restoreTopology() {
        List<Declarable> declarables = new ArrayList<>(taskTopology.getDeclarables());
        for (Declarable declarable : declarables) {
            if (declarable instanceof Exchange exchange) {
                amqpAdmin.declareExchange(exchange);
            }
        }
        for (Declarable declarable : declarables) {
            if (declarable instanceof Queue queue) {
                amqpAdmin.declareQueue(queue);
            }
        }
        for (Declarable declarable : declarables) {
            if (declarable instanceof Binding binding) {
                amqpAdmin.declareBinding(binding);
            }
        }
    }
}
