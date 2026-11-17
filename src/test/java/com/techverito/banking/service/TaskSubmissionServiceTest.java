package com.techverito.banking.service;

import com.techverito.banking.dto.TaskAcceptedResponse;
import com.techverito.banking.dto.TaskStatusResponse;
import com.techverito.banking.entity.BackgroundTask;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.entity.BackgroundTaskType;
import com.techverito.banking.entity.ExportType;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventFactory;
import com.techverito.banking.event.BusinessEventJson;
import com.techverito.banking.event.DocumentScanRequestedPayload;
import com.techverito.banking.event.ExportRequestedPayload;
import com.techverito.banking.event.StatementRequestedPayload;
import com.techverito.banking.event.SubjectType;
import com.techverito.banking.event.WelcomeEmailRequestedPayload;
import com.techverito.banking.exception.BusinessEventValidationException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.BackgroundTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task submission against a real outbox table: the envelope that is stored, the business
 * key that makes a repeated submission the same task, and the transaction the row is
 * written in - a rolled-back submission must leave nothing behind.
 */
@DataJpaTest
class TaskSubmissionServiceTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final String CORRELATION_ID = "corr-2025-03-04-0001";

    @Autowired
    BackgroundTaskRepository backgroundTaskRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final BusinessEventJson eventJson = new BusinessEventJson();

    private TaskSubmissionService taskSubmissionService;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        taskSubmissionService = new TaskSubmissionService(new BusinessEventFactory(clock), eventJson,
                backgroundTaskRepository, clock);
    }

    /** The stored outbox row for one business key, which every assertion below starts from. */
    private BackgroundTask row(BackgroundTaskType taskType, String businessKey) {
        Optional<BackgroundTask> found = backgroundTaskRepository.findByTaskTypeAndBusinessKey(taskType, businessKey);
        assertThat(found).as("a %s task with business key %s must be stored", taskType, businessKey).isPresent();
        return found.orElseThrow();
    }

    @Test
    void submitDocumentScan_twice_returnsTheSameTaskAndStoresOneRowWithOneEnvelope() {
        TaskAcceptedResponse first = taskSubmissionService.submitDocumentScan(1L, 7L, "sample-valid.pdf",
                "ops.user", CORRELATION_ID);
        TaskAcceptedResponse second = taskSubmissionService.submitDocumentScan(1L, 7L, "sample-valid.pdf",
                "ops.user", "corr-2025-03-04-0002");

        assertThat(second.taskId()).isEqualTo(first.taskId());
        assertThat(first.taskType()).isEqualTo("DOCUMENT_SCAN");
        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(backgroundTaskRepository.count()).isEqualTo(1L);

        BackgroundTask stored = row(BackgroundTaskType.DOCUMENT_SCAN, "7");

        // The duplicate submission did not replace the message: one envelope, one event id.
        assertThat(stored.getCorrelationId()).isEqualTo(CORRELATION_ID);
        BusinessEvent<DocumentScanRequestedPayload> event =
                eventJson.fromJson(stored.getMessageJson(), DocumentScanRequestedPayload.class);
        assertThat(stored.getEventId()).isEqualTo(event.eventId().toString());
        assertThat(event.type()).isEqualTo("document.scan.requested");
        assertThat(event.payload()).isEqualTo(new DocumentScanRequestedPayload(7L, 1L, "sample-valid.pdf"));
    }

    @Test
    void submitDocumentScan_differentDocuments_createTwoTasks() {
        TaskAcceptedResponse first = taskSubmissionService.submitDocumentScan(1L, 7L, "first.pdf", "ops.user",
                CORRELATION_ID);
        TaskAcceptedResponse second = taskSubmissionService.submitDocumentScan(1L, 8L, "second.pdf", "ops.user",
                CORRELATION_ID);

        assertThat(second.taskId()).isNotEqualTo(first.taskId());
        assertThat(backgroundTaskRepository.count()).isEqualTo(2L);
        assertThat(row(BackgroundTaskType.DOCUMENT_SCAN, "8").getBusinessKey()).isEqualTo("8");
    }

    @Test
    void submitWelcomeEmail_storesTheDocumentedEnvelopeAsAPendingRow() {
        TaskAcceptedResponse accepted = taskSubmissionService.submitWelcomeEmail(5L, "jane@example.com",
                "ops.user", CORRELATION_ID);

        BackgroundTask stored = row(BackgroundTaskType.WELCOME_EMAIL, "5");
        assertThat(accepted.taskId()).isEqualTo(stored.getId());
        assertThat(accepted.taskType()).isEqualTo("WELCOME_EMAIL");
        assertThat(accepted.status()).isEqualTo("PENDING");
        assertThat(stored.getStatus()).isEqualTo(BackgroundTaskStatus.PENDING);
        assertThat(stored.getEventId()).isNotBlank();
        assertThat(stored.getCorrelationId()).isEqualTo(CORRELATION_ID);
        assertThat(stored.getRequestedBy()).isEqualTo("ops.user");
        assertThat(stored.getAttempts()).isZero();
        assertThat(stored.getNextAttemptAt()).isNull();
        assertThat(stored.getPublishedAt()).isNull();
        assertThat(stored.getCreatedAt()).isEqualTo(NOW);

        BusinessEvent<WelcomeEmailRequestedPayload> event =
                eventJson.fromJson(stored.getMessageJson(), WelcomeEmailRequestedPayload.class);
        assertThat(event.eventId().toString()).isEqualTo(stored.getEventId());
        assertThat(event.type()).isEqualTo("welcome.email.requested");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.subjectType()).isEqualTo(SubjectType.CUSTOMER);
        assertThat(event.subjectId()).isEqualTo(5L);
        assertThat(event.causedBy()).isEqualTo("ops.user");
        assertThat(event.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(event.payload())
                .isEqualTo(new WelcomeEmailRequestedPayload(5L, "jane@example.com", "ops.user"));
    }

    @Test
    void submitStatementGeneration_keysOnAccountAndPeriodAndNamesTheAccountAsSubject() {
        TaskAcceptedResponse accepted = taskSubmissionService.submitStatementGeneration(10L,
                LocalDate.parse("2025-02-01"), LocalDate.parse("2025-02-28"), "ops.user", CORRELATION_ID);

        BackgroundTask stored = row(BackgroundTaskType.STATEMENT_GENERATION, "10:2025-02-01:2025-02-28");
        assertThat(accepted.taskId()).isEqualTo(stored.getId());
        assertThat(accepted.taskType()).isEqualTo("STATEMENT_GENERATION");

        BusinessEvent<StatementRequestedPayload> event =
                eventJson.fromJson(stored.getMessageJson(), StatementRequestedPayload.class);
        assertThat(event.type()).isEqualTo("statement.requested");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.subjectType()).isEqualTo(SubjectType.ACCOUNT);
        assertThat(event.subjectId()).isEqualTo(10L);
        assertThat(event.payload()).isEqualTo(new StatementRequestedPayload(10L, LocalDate.parse("2025-02-01"),
                LocalDate.parse("2025-02-28"), "ops.user"));

        TaskAcceptedResponse otherPeriod = taskSubmissionService.submitStatementGeneration(10L,
                LocalDate.parse("2025-03-01"), LocalDate.parse("2025-03-31"), "ops.user", CORRELATION_ID);

        assertThat(otherPeriod.taskId()).isNotEqualTo(accepted.taskId());
        assertThat(backgroundTaskRepository.count()).isEqualTo(2L);
    }

    @Test
    void submitStatementGeneration_periodStartAfterPeriodEnd_isRejectedAndStoresNothing() {
        assertThatThrownBy(() -> taskSubmissionService.submitStatementGeneration(10L,
                LocalDate.parse("2025-02-28"), LocalDate.parse("2025-02-01"), "ops.user", CORRELATION_ID))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("periodEnd");

        assertThat(backgroundTaskRepository.count()).isZero();
    }

    @Test
    void submitDocumentExport_keysOnTheExportTypeAndTheRequester() {
        TaskAcceptedResponse accepted = taskSubmissionService.submitDocumentExport(ExportType.CUSTOMERS, "ops.user",
                CORRELATION_ID);

        BackgroundTask stored = row(BackgroundTaskType.DOCUMENT_EXPORT, "customers:ops.user");
        assertThat(accepted.taskId()).isEqualTo(stored.getId());
        assertThat(accepted.taskType()).isEqualTo("DOCUMENT_EXPORT");

        BusinessEvent<ExportRequestedPayload> event =
                eventJson.fromJson(stored.getMessageJson(), ExportRequestedPayload.class);
        assertThat(event.type()).isEqualTo("export.requested");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.causedBy()).isEqualTo("ops.user");
        assertThat(event.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(event.payload()).isEqualTo(new ExportRequestedPayload(ExportType.CUSTOMERS, "ops.user",
                "banking-export-2025-03-04-0907.csv"));

        // The same requester asking for the same export again is the same task ...
        TaskAcceptedResponse again = taskSubmissionService.submitDocumentExport(ExportType.CUSTOMERS, "ops.user",
                "corr-2025-03-04-0002");
        assertThat(again.taskId()).isEqualTo(accepted.taskId());
        assertThat(backgroundTaskRepository.count()).isEqualTo(1L);

        // ... while another export type is different work.
        TaskAcceptedResponse accounts = taskSubmissionService.submitDocumentExport(ExportType.ACCOUNTS, "ops.user",
                CORRELATION_ID);
        assertThat(accounts.taskId()).isNotEqualTo(accepted.taskId());
        assertThat(backgroundTaskRepository.count()).isEqualTo(2L);
    }

    @Test
    void blankRequesterOrCorrelationId_isRejectedBeforeAnythingIsStored() {
        assertThatThrownBy(() -> taskSubmissionService.submitWelcomeEmail(5L, "jane@example.com", "  ",
                CORRELATION_ID))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("requestedBy");

        assertThatThrownBy(() -> taskSubmissionService.submitWelcomeEmail(5L, "jane@example.com", "ops.user", " "))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("correlation");

        assertThat(backgroundTaskRepository.count()).isZero();
    }

    @Test
    void status_returnsTheTaskTypeStatusAndNoPublishedTimeWhilePending() {
        TaskAcceptedResponse accepted = taskSubmissionService.submitDocumentScan(1L, 7L, "sample-valid.pdf",
                "ops.user", CORRELATION_ID);

        TaskStatusResponse status = taskSubmissionService.status(accepted.taskId());

        assertThat(status.taskId()).isEqualTo(accepted.taskId());
        assertThat(status.taskType()).isEqualTo("DOCUMENT_SCAN");
        assertThat(status.status()).isEqualTo("PENDING");
        assertThat(status.publishedAt()).isNull();
    }

    @Test
    void status_unknownTask_throwsNotFound() {
        assertThatThrownBy(() -> taskSubmissionService.status(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Task not found with id: 99");
    }

    @Test
    void aSubmissionInARolledBackTransactionStoresNoTask() {
        TransactionTemplate businessTransaction = new TransactionTemplate(transactionManager);
        businessTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        businessTransaction.executeWithoutResult(status -> {
            taskSubmissionService.submitDocumentScan(1L, 7L, "sample-valid.pdf", "ops.user", CORRELATION_ID);
            status.setRollbackOnly();
        });

        assertThat(backgroundTaskRepository.findByTaskTypeAndBusinessKey(BackgroundTaskType.DOCUMENT_SCAN, "7"))
                .isEmpty();
        assertThat(backgroundTaskRepository.count()).isZero();
    }
}
