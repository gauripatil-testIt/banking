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
import com.techverito.banking.event.BusinessEventType;
import com.techverito.banking.event.DocumentScanRequestedPayload;
import com.techverito.banking.event.EventPayload;
import com.techverito.banking.event.ExportRequestedPayload;
import com.techverito.banking.event.StatementRequestedPayload;
import com.techverito.banking.event.SubjectType;
import com.techverito.banking.event.WelcomeEmailRequestedPayload;
import com.techverito.banking.exception.BusinessEventValidationException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.BackgroundTaskRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Turns slow work into an outbox row: it builds the business event with
 * {@link BusinessEventFactory}, writes it with {@link BusinessEventJson} into a
 * {@link BackgroundTaskStatus#PENDING} {@link BackgroundTask} and returns the task id the
 * caller polls.
 *
 * <p>The row is written inside the caller's transaction, so a rolled-back customer
 * creation or document upload leaves no task behind and a committed one always has its
 * task. The row - not this service - is what the scheduled relay publishes, so a broker
 * that is down delays the work instead of failing the request.</p>
 *
 * <p>A repeated submission of the same work is not submitted twice: the business key
 * identifies the work ({@code customerId} for a welcome email, {@code documentId} for a
 * scan, the export type plus the requester for an export, the account plus the statement
 * period for a statement) and the unique constraint behind it, not this lookup, is what
 * makes a duplicate impossible.</p>
 */
@Service
@Transactional
public class TaskSubmissionService {

    /**
     * The envelope requires a subject pair, but a data export covers every customer or
     * every account and so concerns no single record: the export's identity is its
     * business key, the export type plus the requester.
     */
    private static final long NO_SINGLE_RECORD_SUBJECT_ID = 0L;

    /** The export file name stamped into the event, in the same shape the CSV export uses. */
    private static final DateTimeFormatter EXPORT_FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneOffset.UTC);

    private final BusinessEventFactory eventFactory;
    private final BusinessEventJson eventJson;
    private final BackgroundTaskRepository taskRepository;
    private final Clock clock;

    public TaskSubmissionService(BusinessEventFactory eventFactory,
                                BusinessEventJson eventJson,
                                BackgroundTaskRepository taskRepository,
                                Clock clock) {
        this.eventFactory = eventFactory;
        this.eventJson = eventJson;
        this.taskRepository = taskRepository;
        this.clock = clock;
    }

    public TaskAcceptedResponse submitWelcomeEmail(Long customerId, String email, String requestedBy,
                                                   String correlationId) {
        return submit(BackgroundTaskType.WELCOME_EMAIL, String.valueOf(customerId), requestedBy, correlationId,
                () -> eventFactory.create(BusinessEventType.WELCOME_EMAIL_REQUESTED, SubjectType.CUSTOMER,
                        customerId, requestedBy, correlationId,
                        new WelcomeEmailRequestedPayload(customerId, email, requestedBy)));
    }

    public TaskAcceptedResponse submitDocumentScan(Long customerId, Long documentId, String originalFileName,
                                                   String requestedBy, String correlationId) {
        return submit(BackgroundTaskType.DOCUMENT_SCAN, String.valueOf(documentId), requestedBy, correlationId,
                () -> eventFactory.create(BusinessEventType.DOCUMENT_SCAN_REQUESTED, SubjectType.CUSTOMER,
                        customerId, requestedBy, correlationId,
                        new DocumentScanRequestedPayload(documentId, customerId, originalFileName)));
    }

    public TaskAcceptedResponse submitDocumentExport(ExportType exportType, String exportedBy,
                                                     String correlationId) {
        return submit(BackgroundTaskType.DOCUMENT_EXPORT, exportKey(exportType, exportedBy), exportedBy,
                correlationId,
                () -> eventFactory.create(BusinessEventType.EXPORT_REQUESTED, SubjectType.CUSTOMER,
                        NO_SINGLE_RECORD_SUBJECT_ID, exportedBy, correlationId,
                        new ExportRequestedPayload(exportType, exportedBy, exportFileName())));
    }

    public TaskAcceptedResponse submitStatementGeneration(Long accountId, LocalDate periodStart,
                                                          LocalDate periodEnd, String requestedBy,
                                                          String correlationId) {
        return submit(BackgroundTaskType.STATEMENT_GENERATION, statementKey(accountId, periodStart, periodEnd),
                requestedBy, correlationId,
                () -> eventFactory.create(BusinessEventType.STATEMENT_REQUESTED, SubjectType.ACCOUNT,
                        accountId, requestedBy, correlationId,
                        new StatementRequestedPayload(accountId, periodStart, periodEnd, requestedBy)));
    }

    @Transactional(readOnly = true)
    public TaskStatusResponse status(Long taskId) {
        return taskRepository.findById(taskId)
                .map(TaskStatusResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId));
    }

    /**
     * Writes one PENDING row for the work, or reports the row that already describes it.
     * The event is only built when the work is new, so a duplicate submission adds no
     * message and no event id.
     */
    private TaskAcceptedResponse submit(BackgroundTaskType taskType, String businessKey, String requestedBy,
                                        String correlationId,
                                        Supplier<BusinessEvent<? extends EventPayload>> event) {
        requireRequester(requestedBy);
        requireCorrelationId(correlationId);

        Optional<BackgroundTask> alreadySubmitted =
                taskRepository.findByTaskTypeAndBusinessKey(taskType, businessKey);
        if (alreadySubmitted.isPresent()) {
            return TaskAcceptedResponse.from(alreadySubmitted.get());
        }

        BusinessEvent<? extends EventPayload> businessEvent = event.get();
        BackgroundTask task = BackgroundTask.builder()
                .taskType(taskType)
                .businessKey(businessKey)
                .status(BackgroundTaskStatus.PENDING)
                .eventId(businessEvent.eventId().toString())
                .correlationId(correlationId)
                .requestedBy(requestedBy)
                .messageJson(eventJson.toJson(businessEvent))
                .createdAt(Instant.now(clock))
                .build();
        try {
            return TaskAcceptedResponse.from(taskRepository.saveAndFlush(task));
        } catch (DataIntegrityViolationException ex) {
            // A concurrent submission of the same business key won the race; the row that
            // is already there is the task both callers report.
            return taskRepository.findByTaskTypeAndBusinessKey(taskType, businessKey)
                    .map(TaskAcceptedResponse::from)
                    .orElseThrow(() -> ex);
        }
    }

    private void requireRequester(String requestedBy) {
        if (requestedBy == null || requestedBy.isBlank()) {
            throw new BusinessEventValidationException(
                    "The name of the user requesting the background task (requestedBy) is required.");
        }
    }

    private void requireCorrelationId(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new BusinessEventValidationException(
                    "A correlation id is required on every background task submission.");
        }
    }

    private String exportKey(ExportType exportType, String exportedBy) {
        if (exportType == null) {
            throw new BusinessEventValidationException("An export type is required to request an export.");
        }
        return exportType.getWireValue() + ":" + exportedBy;
    }

    private String statementKey(Long accountId, LocalDate periodStart, LocalDate periodEnd) {
        return accountId + ":" + periodStart + ":" + periodEnd;
    }

    private String exportFileName() {
        return "banking-export-" + EXPORT_FILE_TIMESTAMP.format(Instant.now(clock)) + ".csv";
    }
}
