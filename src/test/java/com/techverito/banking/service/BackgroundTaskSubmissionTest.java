package com.techverito.banking.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.AccountRequest;
import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.entity.BackgroundTaskOutboxEntry;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.entity.BusinessEventOutboxEntry;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.event.EventType;
import com.techverito.banking.event.task.BackgroundTaskOutboxRecorder;
import com.techverito.banking.event.task.TaskType;
import com.techverito.banking.repository.BackgroundTaskOutboxRepository;
import com.techverito.banking.repository.BusinessEventOutboxRepository;
import com.techverito.banking.repository.CustomerRepository;
import com.techverito.banking.repository.DataExportRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every business flow that asks for background work: what it writes to the
 * background-task outbox, and that the BANK-7 business-event row it already wrote is still
 * written. Real HTTP endpoints, the real services, the real database (H2) – only the
 * broker is absent, which is exactly what the outbox is for.
 *
 * <p>The drain is configured far into the future, so the rows the flows record stay
 * {@code PENDING} for the length of the test instead of being published to a broker that
 * is not running.</p>
 */
@SpringBootTest(properties = {
        "banking.events.transport=in-memory",
        "banking.tasks.drain-interval=PT1H",
        "banking.documents.storage=local",
        "banking.documents.storage-dir=./target/test-documents/background-tasks",
        "spring.rabbitmq.dynamic=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackgroundTaskSubmissionTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    BackgroundTaskOutboxRepository taskOutboxRepository;

    @Autowired
    BusinessEventOutboxRepository businessEventOutboxRepository;

    @Autowired
    DataExportRepository dataExportRepository;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    ExportService exportService;

    /** Registers a customer through the real endpoint and answers its id. */
    private Long createCustomer(String actor, String correlationId) throws Exception {
        CustomerRequest request = new CustomerRequest("John", "Doe",
                "john-" + UUID.randomUUID() + "@example.com", "1234567890", CustomerStatus.ACTIVE);
        MvcResult result = mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor", actor)
                        .header("X-Correlation-Id", correlationId)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    /** Opens an account through the real endpoint and answers its id. */
    private Long createAccount(Long customerId) throws Exception {
        AccountRequest request = new AccountRequest(customerId, "ACC-" + UUID.randomUUID(),
                AccountType.SAVINGS, new BigDecimal("1000.00"), AccountStatus.ACTIVE);
        MvcResult result = mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return idOf(result);
    }

    private Long idOf(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return body.get("id").asLong();
    }

    /** The single stored task row a flow is expected to have recorded, found by its derived id. */
    private BackgroundTaskOutboxEntry taskRow(TaskType type, String recordType, Long recordId) {
        String taskId = BackgroundTaskOutboxRecorder.taskIdFor(type, recordType, recordId);
        return taskOutboxRepository.findAll().stream()
                .filter(row -> taskId.equals(row.getTaskId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No background task row " + taskId + " for " + type + " "
                        + recordType + " " + recordId));
    }

    private List<BusinessEventOutboxEntry> eventsOfCustomer(Long customerId) {
        return businessEventOutboxRepository.findByBusinessKeyOrderByIdAsc("customer:" + customerId);
    }

    @Test
    void postCustomer_recordsAWelcomeEmailTaskAndStillWritesTheBusinessEvent() throws Exception {
        Long customerId = createCustomer("ops.user", "corr-bank-8-customer");

        BackgroundTaskOutboxEntry task = taskRow(TaskType.WELCOME_EMAIL, "customer", customerId);
        assertThat(task.getTaskType()).isEqualTo(TaskType.WELCOME_EMAIL);
        assertThat(task.getRecordType()).isEqualTo("customer");
        assertThat(task.getRecordId()).isEqualTo(customerId);
        assertThat(task.getCustomerId()).isEqualTo(customerId);
        assertThat(task.getStatus()).isEqualTo(BackgroundTaskStatus.PENDING);
        assertThat(task.getAttempts()).isZero();
        assertThat(task.getRequestedBy()).isEqualTo("ops.user");
        assertThat(task.getCorrelationId()).isEqualTo("corr-bank-8-customer");
        assertThat(task.getPayload()).isNotBlank();
        assertThat(task.getPublishedAt()).isNull();

        assertThat(eventsOfCustomer(customerId)).extracting(BusinessEventOutboxEntry::getEventType)
                .containsExactly(EventType.CUSTOMER_REGISTERED);
    }

    @Test
    void postDocument_recordsADocumentScanTaskForTheStoredDocument() throws Exception {
        Long customerId = createCustomer("ops.user", "corr-bank-8-document-customer");
        MockMultipartFile file = new MockMultipartFile("file", "proof.pdf", "application/pdf",
                "pdf-content".getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/customers/{id}/documents", customerId)
                        .file(file)
                        .param("documentType", "ID_PROOF")
                        .param("uploadedBy", "ops.user")
                        .header("X-Actor", "ops.user")
                        .header("X-Correlation-Id", "corr-bank-8-document"))
                .andExpect(status().isCreated())
                .andReturn();
        Long documentId = idOf(result);

        BackgroundTaskOutboxEntry task = taskRow(TaskType.DOCUMENT_SCAN, "document", documentId);
        assertThat(task.getTaskType()).isEqualTo(TaskType.DOCUMENT_SCAN);
        assertThat(task.getRecordType()).isEqualTo("document");
        assertThat(task.getRecordId()).isEqualTo(documentId);
        assertThat(task.getCustomerId()).isEqualTo(customerId);
        assertThat(task.getStatus()).isEqualTo(BackgroundTaskStatus.PENDING);
        assertThat(task.getRequestedBy()).isEqualTo("ops.user");
        assertThat(task.getCorrelationId()).isEqualTo("corr-bank-8-document");

        assertThat(eventsOfCustomer(customerId)).extracting(BusinessEventOutboxEntry::getEventType)
                .contains(EventType.DOCUMENT_UPLOADED);
    }

    @Test
    void rejectedDocumentUpload_recordsNoScanTask() throws Exception {
        Long customerId = createCustomer("ops.user", "corr-bank-8-rejected-customer");
        MockMultipartFile wrongType = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/customers/{id}/documents", customerId)
                        .file(wrongType)
                        .param("documentType", "ID_PROOF")
                        .param("uploadedBy", "ops.user"))
                .andExpect(status().isBadRequest());

        assertThat(taskOutboxRepository.findAll())
                .noneMatch(row -> row.getTaskType() == TaskType.DOCUMENT_SCAN
                        && customerId.equals(row.getCustomerId()));
    }

    @Test
    void export_recordsADocumentExportTaskAlongsideTheAuditRow() throws Exception {
        createCustomer("ops.user", "corr-bank-8-export-customer");
        Long firstCustomerId = customerRepository.findAll().stream()
                .map(Customer::getId)
                .min(Long::compareTo)
                .orElseThrow();
        long auditsBefore = dataExportRepository.count();

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Actor", "ops.user");
        request.addHeader("X-Correlation-Id", "corr-bank-8-export");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            exportService.write(exportService.prepare("customers", "ops.user"), new ByteArrayOutputStream());
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }

        assertThat(dataExportRepository.count()).isEqualTo(auditsBefore + 1);

        BackgroundTaskOutboxEntry task = taskRow(TaskType.DOCUMENT_EXPORT, "data_export", firstCustomerId);
        assertThat(task.getTaskType()).isEqualTo(TaskType.DOCUMENT_EXPORT);
        assertThat(task.getCustomerId()).isEqualTo(firstCustomerId);
        assertThat(task.getStatus()).isEqualTo(BackgroundTaskStatus.PENDING);
        assertThat(task.getRequestedBy()).isEqualTo("ops.user");
        assertThat(task.getCorrelationId()).isEqualTo("corr-bank-8-export");

        assertThat(eventsOfCustomer(firstCustomerId)).extracting(BusinessEventOutboxEntry::getEventType)
                .contains(EventType.EXPORT_REQUESTED);
    }

    @Test
    void postStatement_answers202AndQueuesTheStatementOnce() throws Exception {
        Long customerId = createCustomer("ops.user", "corr-bank-8-statement-customer");
        Long accountId = createAccount(customerId);

        MvcResult first = mockMvc.perform(post("/accounts/{id}/statements", accountId)
                        .header("X-Actor", "ops.user")
                        .header("X-Correlation-Id", "corr-bank-8-statement"))
                .andExpect(status().isAccepted())
                .andReturn();
        JsonNode body = objectMapper.readTree(first.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String taskId = body.get("taskId").asText();
        assertThat(body.get("taskType").asText()).isEqualTo("STATEMENT_GENERATION");
        assertThat(body.get("accountId").asLong()).isEqualTo(accountId);

        // The same statement request again: accepted with the same task, and no second row.
        MvcResult second = mockMvc.perform(post("/accounts/{id}/statements", accountId)
                        .header("X-Actor", "ops.user")
                        .header("X-Correlation-Id", "corr-bank-8-statement-again"))
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(objectMapper.readTree(second.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("taskId").asText()).isEqualTo(taskId);

        List<BackgroundTaskOutboxEntry> rows = taskOutboxRepository.findAll().stream()
                .filter(row -> taskId.equals(row.getTaskId()))
                .toList();
        assertThat(rows).hasSize(1);
        BackgroundTaskOutboxEntry task = rows.get(0);
        assertThat(task.getTaskType()).isEqualTo(TaskType.STATEMENT_GENERATION);
        assertThat(task.getRecordType()).isEqualTo("account");
        assertThat(task.getRecordId()).isEqualTo(accountId);
        assertThat(task.getCustomerId()).isEqualTo(customerId);
        assertThat(task.getStatus()).isEqualTo(BackgroundTaskStatus.PENDING);
        assertThat(task.getRequestedBy()).isEqualTo("ops.user");
        assertThat(task.getCorrelationId()).isEqualTo("corr-bank-8-statement");
    }

    @Test
    void postStatement_forAnUnknownAccount_answers404AndQueuesNothing() throws Exception {
        long unknownAccountId = 999_999L;

        mockMvc.perform(post("/accounts/{id}/statements", unknownAccountId))
                .andExpect(status().isNotFound());

        String taskId = BackgroundTaskOutboxRecorder.taskIdFor(TaskType.STATEMENT_GENERATION, "account",
                unknownAccountId);
        assertThat(taskOutboxRepository.existsByTaskId(taskId)).isFalse();
    }
}
