package com.techverito.banking.event.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.techverito.banking.entity.BackgroundTaskOutboxEntry;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.event.outbox.EventActorContext;
import com.techverito.banking.repository.BackgroundTaskOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a business action stores in the background-task outbox, against the real database
 * the application runs on (H2 in the tests, the entity mapping that Oracle gets as DDL).
 * No broker is involved: the recorder never talks to one, which is what lets a customer
 * action commit while RabbitMQ is down.
 *
 * <p>The test is non-transactional, so the rows it writes are committed the way a business
 * transaction commits them and a rolled-back business transaction can be shown to leave
 * nothing behind.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackgroundTaskOutboxRecorderTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    @Autowired
    BackgroundTaskOutboxRepository outboxRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** The mapper the application uses: ISO-8601 instants, no numeric timestamps. */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private BackgroundTaskOutboxRecorder recorder;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        RequestContextHolder.resetRequestAttributes();
        recorder = new BackgroundTaskOutboxRecorder(outboxRepository, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), new EventActorContext());
    }

    /** The stored rows in the order the drain takes them. */
    private List<BackgroundTaskOutboxEntry> storedRows() {
        return outboxRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    @Test
    void submit_welcomeEmail_storesOnePendingRowCarryingTheWholeMessage() throws Exception {
        BackgroundTaskMessage submitted = recorder.submit(TaskType.WELCOME_EMAIL, "customer", 1L, 1L);

        List<BackgroundTaskOutboxEntry> rows = storedRows();
        assertThat(rows).hasSize(1);

        BackgroundTaskOutboxEntry row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo(BackgroundTaskStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getTaskId()).isEqualTo(submitted.taskId());
        assertThat(row.getTaskId())
                .isEqualTo(BackgroundTaskMessage.taskIdFor(TaskType.WELCOME_EMAIL, "customer", 1L));
        assertThat(row.getTaskType()).isEqualTo(TaskType.WELCOME_EMAIL);
        assertThat(row.getRecordType()).isEqualTo("customer");
        assertThat(row.getRecordId()).isEqualTo(1L);
        assertThat(row.getCustomerId()).isEqualTo(1L);
        assertThat(row.getRequestedBy()).isEqualTo("system");
        assertThat(row.getCorrelationId()).isNotBlank();
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getLastError()).isNull();

        JsonNode payload = objectMapper.readTree(row.getPayload());
        assertThat(payload.get("taskId").asText()).isEqualTo(submitted.taskId());
        assertThat(payload.get("type").asText()).isEqualTo("WELCOME_EMAIL");
        assertThat(payload.get("recordType").asText()).isEqualTo("customer");
        assertThat(payload.get("recordId").asLong()).isEqualTo(1L);
        assertThat(payload.get("customerId").asLong()).isEqualTo(1L);
        assertThat(payload.get("requestedBy").asText()).isEqualTo(submitted.requestedBy());
        assertThat(payload.get("correlationId").asText()).isEqualTo(submitted.correlationId());
        assertThat(payload.get("occurredAt").asText()).isEqualTo("2025-03-04T09:07:45Z");

        assertThat(objectMapper.readValue(row.getPayload(), BackgroundTaskMessage.class)).isEqualTo(submitted);
    }

    @Test
    void submit_storesOnePendingRowPerTaskTypeForTheRecordItAppliesTo() {
        recorder.submit(TaskType.WELCOME_EMAIL, "customer", 1L, 1L);
        recorder.submit(TaskType.STATEMENT_GENERATION, "account", 9L, 1L);
        recorder.submit(TaskType.DOCUMENT_EXPORT, "data_export", 3L, 1L);
        recorder.submit(TaskType.DOCUMENT_SCAN, "document", 4L, 1L);

        List<BackgroundTaskOutboxEntry> rows = storedRows();

        assertThat(rows).extracting(BackgroundTaskOutboxEntry::getTaskType).containsExactly(
                TaskType.WELCOME_EMAIL,
                TaskType.STATEMENT_GENERATION,
                TaskType.DOCUMENT_EXPORT,
                TaskType.DOCUMENT_SCAN);
        assertThat(rows).extracting(BackgroundTaskOutboxEntry::getRecordType).containsExactly(
                "customer", "account", "data_export", "document");
        assertThat(rows).extracting(BackgroundTaskOutboxEntry::getRecordId)
                .containsExactly(1L, 9L, 3L, 4L);
        assertThat(rows).extracting(BackgroundTaskOutboxEntry::getStatus)
                .containsOnly(BackgroundTaskStatus.PENDING);
        assertThat(rows).extracting(BackgroundTaskOutboxEntry::getTaskId).doesNotHaveDuplicates();
    }

    @Test
    void submit_takesTheRequesterAndTheCorrelationIdFromTheRequestHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Actor", "ops.user");
        request.addHeader("X-Correlation-Id", "corr-bank-8-document");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        BackgroundTaskMessage submitted = recorder.submit(TaskType.DOCUMENT_SCAN, "document", 7L, 3L);

        BackgroundTaskOutboxEntry row = storedRows().get(0);
        assertThat(submitted.requestedBy()).isEqualTo("ops.user");
        assertThat(submitted.correlationId()).isEqualTo("corr-bank-8-document");
        assertThat(row.getRequestedBy()).isEqualTo("ops.user");
        assertThat(row.getCorrelationId()).isEqualTo("corr-bank-8-document");
        assertThat(row.getCustomerId()).isEqualTo(3L);
    }

    @Test
    void submit_theSameTaskForTheSameRecordTwice_writesOneRowAndReturnsTheSameTaskId() {
        BackgroundTaskMessage first = recorder.submit(TaskType.DOCUMENT_SCAN, "document", 7L, 3L);
        BackgroundTaskMessage second = recorder.submit(TaskType.DOCUMENT_SCAN, "document", 7L, 3L);

        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(storedRows()).hasSize(1);
        assertThat(outboxRepository.existsByTaskId(first.taskId())).isTrue();
    }

    @Test
    void submit_differentTasksForTheSameRecord_areStoredSeparately() {
        BackgroundTaskMessage scan = recorder.submit(TaskType.DOCUMENT_SCAN, "document", 7L, 3L);
        BackgroundTaskMessage statement = recorder.submit(TaskType.STATEMENT_GENERATION, "document", 7L, 3L);

        assertThat(statement.taskId()).isNotEqualTo(scan.taskId());
        assertThat(storedRows()).hasSize(2);
    }

    @Test
    void submit_leavesNoRowWhenTheBusinessTransactionRollsBack() {
        TransactionTemplate businessTransaction = new TransactionTemplate(transactionManager);
        businessTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        businessTransaction.executeWithoutResult(status -> {
            BackgroundTaskMessage submitted = recorder.submit(TaskType.WELCOME_EMAIL, "customer", 1L, 1L);
            assertThat(outboxRepository.existsByTaskId(submitted.taskId())).isTrue();
            status.setRollbackOnly();
        });

        assertThat(outboxRepository.count()).isZero();
    }
}
