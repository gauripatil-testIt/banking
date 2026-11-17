package com.techverito.banking.event;

import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.entity.ExportType;
import com.techverito.banking.exception.BusinessEventValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The wire contract of a business event: the nine documented types, the envelope
 * invariants and one fully populated envelope per payload type.
 */
class BusinessEventFormatTest {

    private static final UUID EVENT_ID = UUID.fromString("3f1d2c9a-8b7e-4c6d-9f01-2a3b4c5d6e7f");

    private static final Instant OCCURRED_AT = Instant.parse("2025-03-04T09:07:45Z");

    private static final String CAUSED_BY = "ops.user";

    private static final String CORRELATION_ID = "corr-2025-03-04-0001";

    private static final Map<BusinessEventType, String> DOCUMENTED_WIRE_VALUES = Map.of(
            BusinessEventType.CUSTOMER_REGISTERED, "customer.registered",
            BusinessEventType.ACCOUNT_OPENED, "account.opened",
            BusinessEventType.ACCOUNT_STATUS_CHANGED, "account.status.changed",
            BusinessEventType.DOCUMENT_UPLOADED, "document.uploaded",
            BusinessEventType.EXPORT_REQUESTED, "export.requested",
            BusinessEventType.STATEMENT_GENERATED, "statement.generated",
            BusinessEventType.WELCOME_EMAIL_REQUESTED, "welcome.email.requested",
            BusinessEventType.STATEMENT_REQUESTED, "statement.requested",
            BusinessEventType.DOCUMENT_SCAN_REQUESTED, "document.scan.requested");

    private static CustomerRegisteredPayload customerRegisteredPayload() {
        return new CustomerRegisteredPayload(1L, "John", "Doe", "john@example.com");
    }

    private static AccountOpenedPayload accountOpenedPayload() {
        return new AccountOpenedPayload(10L, 1L, "00012345", AccountType.SAVINGS, new BigDecimal("1000.00"));
    }

    private static AccountStatusChangedPayload accountStatusChangedPayload() {
        return new AccountStatusChangedPayload(10L, "00012345", AccountStatus.ACTIVE, AccountStatus.CLOSED);
    }

    private static DocumentUploadedPayload documentUploadedPayload() {
        return new DocumentUploadedPayload(7L, 1L, DocumentType.ID_PROOF, "sample-valid.pdf", 650L, "ops.user");
    }

    private static ExportRequestedPayload exportRequestedPayload() {
        return new ExportRequestedPayload(ExportType.CUSTOMERS, "ops.user", "banking-export-2025-03-04-0907.csv");
    }

    private static StatementGeneratedPayload statementGeneratedPayload() {
        return new StatementGeneratedPayload(3L, 10L, LocalDate.parse("2025-02-01"), LocalDate.parse("2025-02-28"),
                "pdf");
    }

    private static WelcomeEmailRequestedPayload welcomeEmailRequestedPayload() {
        return new WelcomeEmailRequestedPayload(1L, "john@example.com", "ops.user");
    }

    private static StatementRequestedPayload statementRequestedPayload() {
        return new StatementRequestedPayload(10L, LocalDate.parse("2025-02-01"), LocalDate.parse("2025-02-28"),
                "ops.user");
    }

    private static DocumentScanRequestedPayload documentScanRequestedPayload() {
        return new DocumentScanRequestedPayload(7L, 1L, "sample-valid.pdf");
    }

    private static BusinessEvent<CustomerRegisteredPayload> validEnvelope() {
        return new BusinessEvent<>(EVENT_ID, BusinessEventType.CUSTOMER_REGISTERED.getWireValue(),
                BusinessEventType.CUSTOMER_REGISTERED.getCurrentVersion(), OCCURRED_AT, SubjectType.CUSTOMER, 1L,
                CAUSED_BY, CORRELATION_ID, customerRegisteredPayload());
    }

    private void assertEnvelope(BusinessEvent<?> event, BusinessEventType type, SubjectType subjectType,
                                Long subjectId) {
        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.type()).isEqualTo(type.getWireValue());
        assertThat(event.version()).isEqualTo(type.getCurrentVersion());
        assertThat(event.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(event.subjectType()).isEqualTo(subjectType);
        assertThat(event.subjectId()).isEqualTo(subjectId);
        assertThat(event.causedBy()).isEqualTo(CAUSED_BY);
        assertThat(event.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(event.payload()).isNotNull();
    }

    @Test
    void nineEventTypesUseTheirDocumentedWireValuesAndVersionOne() {
        assertThat(BusinessEventType.values()).hasSize(9);
        assertThat(DOCUMENTED_WIRE_VALUES).hasSize(9);

        for (BusinessEventType type : BusinessEventType.values()) {
            assertThat(type.getWireValue()).isEqualTo(DOCUMENTED_WIRE_VALUES.get(type));
            assertThat(type.getCurrentVersion()).isEqualTo(1);
            assertThat(BusinessEventType.fromWireValue(type.getWireValue())).contains(type);
        }

        assertThat(BusinessEventType.fromWireValue(null)).isEmpty();
        assertThat(BusinessEventType.fromWireValue("customer.unknown")).isEmpty();

        assertThat(SubjectType.values()).containsExactly(SubjectType.CUSTOMER, SubjectType.ACCOUNT);
    }

    @Test
    void rejectsMissingOrInvalidRequiredFields() {
        // Envelope invariants: every one of these constructions is incomplete in exactly one way.
        assertThatThrownBy(() -> new BusinessEvent<>(null, "customer.registered", 1, OCCURRED_AT,
                SubjectType.CUSTOMER, 1L, CAUSED_BY, CORRELATION_ID, customerRegisteredPayload()))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("event id");

        assertThatThrownBy(() -> new BusinessEvent<>(EVENT_ID, "  ", 1, OCCURRED_AT,
                SubjectType.CUSTOMER, 1L, CAUSED_BY, CORRELATION_ID, customerRegisteredPayload()))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("type");

        assertThatThrownBy(() -> new BusinessEvent<>(EVENT_ID, "customer.registered", 0, OCCURRED_AT,
                SubjectType.CUSTOMER, 1L, CAUSED_BY, CORRELATION_ID, customerRegisteredPayload()))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("version");

        assertThatThrownBy(() -> new BusinessEvent<>(EVENT_ID, "customer.registered", null, OCCURRED_AT,
                SubjectType.CUSTOMER, 1L, CAUSED_BY, CORRELATION_ID, customerRegisteredPayload()))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("version");

        assertThatThrownBy(() -> new BusinessEvent<>(EVENT_ID, "customer.registered", 1, null,
                SubjectType.CUSTOMER, 1L, CAUSED_BY, CORRELATION_ID, customerRegisteredPayload()))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("occurred");

        assertThatThrownBy(() -> new BusinessEvent<>(EVENT_ID, "customer.registered", 1, OCCURRED_AT,
                null, 1L, CAUSED_BY, CORRELATION_ID, customerRegisteredPayload()))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("subjectId");

        assertThatThrownBy(() -> new BusinessEvent<>(EVENT_ID, "customer.registered", 1, OCCURRED_AT,
                SubjectType.CUSTOMER, null, CAUSED_BY, CORRELATION_ID, customerRegisteredPayload()))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("subjectId");

        assertThatThrownBy(() -> new BusinessEvent<>(EVENT_ID, "customer.registered", 1, OCCURRED_AT,
                SubjectType.CUSTOMER, 1L, " ", CORRELATION_ID, customerRegisteredPayload()))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("causedBy");

        assertThatThrownBy(() -> new BusinessEvent<>(EVENT_ID, "customer.registered", 1, OCCURRED_AT,
                SubjectType.CUSTOMER, 1L, CAUSED_BY, "  ", customerRegisteredPayload()))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("correlation id");

        assertThatThrownBy(() -> new BusinessEvent<CustomerRegisteredPayload>(EVENT_ID, "customer.registered", 1,
                OCCURRED_AT, SubjectType.CUSTOMER, 1L, CAUSED_BY, CORRELATION_ID, null))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("payload");

        // Payload invariants: the identifying field of each payload is required.
        assertThatThrownBy(() -> new CustomerRegisteredPayload(null, "John", "Doe", "john@example.com"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("customer id");
        assertThatThrownBy(() -> new CustomerRegisteredPayload(1L, "John", "Doe", " "))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("email");

        assertThatThrownBy(() -> new AccountOpenedPayload(null, 1L, "00012345", AccountType.SAVINGS,
                new BigDecimal("1000.00")))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("account id");
        assertThatThrownBy(() -> new AccountOpenedPayload(10L, null, "00012345", AccountType.SAVINGS,
                new BigDecimal("1000.00")))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("customer id");

        assertThatThrownBy(() -> new AccountStatusChangedPayload(null, "00012345", AccountStatus.ACTIVE,
                AccountStatus.CLOSED))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("account id");
        assertThatThrownBy(() -> new AccountStatusChangedPayload(10L, "00012345", null, AccountStatus.CLOSED))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("previous");
        assertThatThrownBy(() -> new AccountStatusChangedPayload(10L, "00012345", AccountStatus.ACTIVE, null))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("new");

        assertThatThrownBy(() -> new DocumentUploadedPayload(null, 1L, DocumentType.ID_PROOF, "sample-valid.pdf",
                650L, "ops.user"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("document id");
        assertThatThrownBy(() -> new DocumentUploadedPayload(7L, 1L, null, "sample-valid.pdf", 650L, "ops.user"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("document type");

        assertThatThrownBy(() -> new ExportRequestedPayload(null, "ops.user", "export.csv"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("export type");
        assertThatThrownBy(() -> new ExportRequestedPayload(ExportType.CUSTOMERS, " ", "export.csv"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("exportedBy");

        assertThatThrownBy(() -> new StatementGeneratedPayload(null, 10L, LocalDate.parse("2025-02-01"),
                LocalDate.parse("2025-02-28"), "pdf"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("statement id");
        assertThatThrownBy(() -> new StatementGeneratedPayload(3L, null, LocalDate.parse("2025-02-01"),
                LocalDate.parse("2025-02-28"), "pdf"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("account id");

        assertThatThrownBy(() -> new WelcomeEmailRequestedPayload(null, "john@example.com", "ops.user"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("customer id");
        assertThatThrownBy(() -> new WelcomeEmailRequestedPayload(1L, " ", "ops.user"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("email");
        assertThatThrownBy(() -> new WelcomeEmailRequestedPayload(1L, "john@example.com", " "))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("requestedBy");

        assertThatThrownBy(() -> new DocumentScanRequestedPayload(null, 1L, "sample-valid.pdf"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("document id");
        assertThatThrownBy(() -> new DocumentScanRequestedPayload(7L, null, "sample-valid.pdf"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("customer id");

        assertThatThrownBy(() -> new StatementRequestedPayload(null, LocalDate.parse("2025-02-01"),
                LocalDate.parse("2025-02-28"), "ops.user"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("account id");
        assertThatThrownBy(() -> new StatementRequestedPayload(10L, null, LocalDate.parse("2025-02-28"),
                "ops.user"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("periodStart");
        assertThatThrownBy(() -> new StatementRequestedPayload(10L, LocalDate.parse("2025-02-01"), null,
                "ops.user"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("periodEnd");
        assertThatThrownBy(() -> new StatementRequestedPayload(10L, LocalDate.parse("2025-02-01"),
                LocalDate.parse("2025-02-28"), " "))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("requestedBy");
        assertThatThrownBy(() -> new StatementRequestedPayload(10L, LocalDate.parse("2025-02-28"),
                LocalDate.parse("2025-02-01"), "ops.user"))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("periodEnd");
    }

    @Test
    void buildsOneEnvelopePerEventType() {
        BusinessEvent<CustomerRegisteredPayload> registered = validEnvelope();
        assertEnvelope(registered, BusinessEventType.CUSTOMER_REGISTERED, SubjectType.CUSTOMER, 1L);
        assertThat(registered.payload()).isEqualTo(customerRegisteredPayload());

        BusinessEvent<AccountOpenedPayload> opened = new BusinessEvent<>(EVENT_ID,
                BusinessEventType.ACCOUNT_OPENED.getWireValue(), 1, OCCURRED_AT, SubjectType.ACCOUNT, 10L,
                CAUSED_BY, CORRELATION_ID, accountOpenedPayload());
        assertEnvelope(opened, BusinessEventType.ACCOUNT_OPENED, SubjectType.ACCOUNT, 10L);
        assertThat(opened.payload().accountId()).isEqualTo(10L);
        assertThat(opened.payload().customerId()).isEqualTo(1L);
        assertThat(opened.payload().accountNumber()).isEqualTo("00012345");
        assertThat(opened.payload().accountType()).isEqualTo(AccountType.SAVINGS);
        assertThat(opened.payload().openingBalance()).isEqualByComparingTo(new BigDecimal("1000.00"));

        BusinessEvent<AccountStatusChangedPayload> statusChanged = new BusinessEvent<>(EVENT_ID,
                BusinessEventType.ACCOUNT_STATUS_CHANGED.getWireValue(), 1, OCCURRED_AT, SubjectType.ACCOUNT, 10L,
                CAUSED_BY, CORRELATION_ID, accountStatusChangedPayload());
        assertEnvelope(statusChanged, BusinessEventType.ACCOUNT_STATUS_CHANGED, SubjectType.ACCOUNT, 10L);
        assertThat(statusChanged.payload().previousStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(statusChanged.payload().newStatus()).isEqualTo(AccountStatus.CLOSED);

        BusinessEvent<DocumentUploadedPayload> documentUploaded = new BusinessEvent<>(EVENT_ID,
                BusinessEventType.DOCUMENT_UPLOADED.getWireValue(), 1, OCCURRED_AT, SubjectType.CUSTOMER, 1L,
                CAUSED_BY, CORRELATION_ID, documentUploadedPayload());
        assertEnvelope(documentUploaded, BusinessEventType.DOCUMENT_UPLOADED, SubjectType.CUSTOMER, 1L);
        assertThat(documentUploaded.payload().documentId()).isEqualTo(7L);
        assertThat(documentUploaded.payload().documentType()).isEqualTo(DocumentType.ID_PROOF);
        assertThat(documentUploaded.payload().originalFileName()).isEqualTo("sample-valid.pdf");
        assertThat(documentUploaded.payload().sizeBytes()).isEqualTo(650L);
        assertThat(documentUploaded.payload().uploadedBy()).isEqualTo("ops.user");

        BusinessEvent<ExportRequestedPayload> exportRequested = new BusinessEvent<>(EVENT_ID,
                BusinessEventType.EXPORT_REQUESTED.getWireValue(), 1, OCCURRED_AT, SubjectType.CUSTOMER, 1L,
                CAUSED_BY, CORRELATION_ID, exportRequestedPayload());
        assertEnvelope(exportRequested, BusinessEventType.EXPORT_REQUESTED, SubjectType.CUSTOMER, 1L);
        assertThat(exportRequested.payload().exportType()).isEqualTo(ExportType.CUSTOMERS);
        assertThat(exportRequested.payload().exportedBy()).isEqualTo("ops.user");
        assertThat(exportRequested.payload().fileName()).isEqualTo("banking-export-2025-03-04-0907.csv");

        BusinessEvent<StatementGeneratedPayload> statementGenerated = new BusinessEvent<>(EVENT_ID,
                BusinessEventType.STATEMENT_GENERATED.getWireValue(), 1, OCCURRED_AT, SubjectType.ACCOUNT, 10L,
                CAUSED_BY, CORRELATION_ID, statementGeneratedPayload());
        assertEnvelope(statementGenerated, BusinessEventType.STATEMENT_GENERATED, SubjectType.ACCOUNT, 10L);
        assertThat(statementGenerated.payload().statementId()).isEqualTo(3L);
        assertThat(statementGenerated.payload().accountId()).isEqualTo(10L);
        assertThat(statementGenerated.payload().periodStart()).isEqualTo(LocalDate.parse("2025-02-01"));
        assertThat(statementGenerated.payload().periodEnd()).isEqualTo(LocalDate.parse("2025-02-28"));
        assertThat(statementGenerated.payload().format()).isEqualTo("pdf");

        BusinessEvent<WelcomeEmailRequestedPayload> welcomeEmailRequested = new BusinessEvent<>(EVENT_ID,
                BusinessEventType.WELCOME_EMAIL_REQUESTED.getWireValue(), 1, OCCURRED_AT, SubjectType.CUSTOMER, 1L,
                CAUSED_BY, CORRELATION_ID, welcomeEmailRequestedPayload());
        assertEnvelope(welcomeEmailRequested, BusinessEventType.WELCOME_EMAIL_REQUESTED, SubjectType.CUSTOMER, 1L);
        assertThat(welcomeEmailRequested.payload()).isEqualTo(welcomeEmailRequestedPayload());

        BusinessEvent<StatementRequestedPayload> statementRequested = new BusinessEvent<>(EVENT_ID,
                BusinessEventType.STATEMENT_REQUESTED.getWireValue(), 1, OCCURRED_AT, SubjectType.ACCOUNT, 10L,
                CAUSED_BY, CORRELATION_ID, statementRequestedPayload());
        assertEnvelope(statementRequested, BusinessEventType.STATEMENT_REQUESTED, SubjectType.ACCOUNT, 10L);
        assertThat(statementRequested.payload()).isEqualTo(statementRequestedPayload());

        BusinessEvent<DocumentScanRequestedPayload> documentScanRequested = new BusinessEvent<>(EVENT_ID,
                BusinessEventType.DOCUMENT_SCAN_REQUESTED.getWireValue(), 1, OCCURRED_AT, SubjectType.CUSTOMER, 1L,
                CAUSED_BY, CORRELATION_ID, documentScanRequestedPayload());
        assertEnvelope(documentScanRequested, BusinessEventType.DOCUMENT_SCAN_REQUESTED, SubjectType.CUSTOMER, 1L);
        assertThat(documentScanRequested.payload()).isEqualTo(documentScanRequestedPayload());
    }
}
