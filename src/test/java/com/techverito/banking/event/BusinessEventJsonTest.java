package com.techverito.banking.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.entity.ExportType;
import com.techverito.banking.exception.BusinessEventValidationException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The JSON wire format of a business event: field names and types, the round trip for
 * every payload, and the forward/backward compatibility the format promises.
 */
class BusinessEventJsonTest {

    private static final UUID EVENT_ID = UUID.fromString("3f1d2c9a-8b7e-4c6d-9f01-2a3b4c5d6e7f");

    private static final Instant OCCURRED_AT = Instant.parse("2025-03-04T09:07:45Z");

    private static final String CAUSED_BY = "ops.user";

    private static final String CORRELATION_ID = "corr-2025-03-04-0001";

    private final BusinessEventJson json = new BusinessEventJson();

    private static <T extends EventPayload> BusinessEvent<T> envelope(BusinessEventType type, SubjectType subjectType,
                                                                     Long subjectId, T payload) {
        return new BusinessEvent<>(EVENT_ID, type.getWireValue(), type.getCurrentVersion(), OCCURRED_AT,
                subjectType, subjectId, CAUSED_BY, CORRELATION_ID, payload);
    }

    private static BusinessEvent<CustomerRegisteredPayload> customerRegistered() {
        return envelope(BusinessEventType.CUSTOMER_REGISTERED, SubjectType.CUSTOMER, 1L,
                new CustomerRegisteredPayload(1L, "John", "Doe", "john@example.com"));
    }

    private static BusinessEvent<AccountOpenedPayload> accountOpened() {
        return envelope(BusinessEventType.ACCOUNT_OPENED, SubjectType.ACCOUNT, 10L,
                new AccountOpenedPayload(10L, 1L, "00012345", AccountType.SAVINGS, new BigDecimal("1000.00")));
    }

    private static BusinessEvent<AccountStatusChangedPayload> accountStatusChanged() {
        return envelope(BusinessEventType.ACCOUNT_STATUS_CHANGED, SubjectType.ACCOUNT, 10L,
                new AccountStatusChangedPayload(10L, "00012345", AccountStatus.ACTIVE, AccountStatus.CLOSED));
    }

    private static BusinessEvent<DocumentUploadedPayload> documentUploaded() {
        return envelope(BusinessEventType.DOCUMENT_UPLOADED, SubjectType.CUSTOMER, 1L,
                new DocumentUploadedPayload(7L, 1L, DocumentType.ID_PROOF, "sample-valid.pdf", 650L, "ops.user"));
    }

    private static BusinessEvent<ExportRequestedPayload> exportRequested() {
        return envelope(BusinessEventType.EXPORT_REQUESTED, SubjectType.CUSTOMER, 1L,
                new ExportRequestedPayload(ExportType.CUSTOMERS, "ops.user", "banking-export-2025-03-04-0907.csv"));
    }

    private static BusinessEvent<StatementGeneratedPayload> statementGenerated() {
        return envelope(BusinessEventType.STATEMENT_GENERATED, SubjectType.ACCOUNT, 10L,
                new StatementGeneratedPayload(3L, 10L, LocalDate.parse("2025-02-01"),
                        LocalDate.parse("2025-02-28"), "pdf"));
    }

    private <T extends EventPayload> BusinessEvent<T> roundTrip(BusinessEvent<T> event, Class<T> payloadType) {
        return json.fromJson(json.toJson(event), payloadType);
    }

    @Test
    void roundTripsEachPayloadType() throws Exception {
        BusinessEvent<CustomerRegisteredPayload> registered = roundTrip(customerRegistered(),
                CustomerRegisteredPayload.class);
        assertThat(registered.eventId()).isEqualTo(EVENT_ID);
        assertThat(registered.type()).isEqualTo("customer.registered");
        assertThat(registered.version()).isEqualTo(1);
        assertThat(registered.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(registered.subjectType()).isEqualTo(SubjectType.CUSTOMER);
        assertThat(registered.subjectId()).isEqualTo(1L);
        assertThat(registered.causedBy()).isEqualTo(CAUSED_BY);
        assertThat(registered.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(registered.payload()).isEqualTo(customerRegistered().payload());

        BusinessEvent<AccountOpenedPayload> opened = roundTrip(accountOpened(), AccountOpenedPayload.class);
        assertThat(opened.type()).isEqualTo("account.opened");
        assertThat(opened.subjectType()).isEqualTo(SubjectType.ACCOUNT);
        assertThat(opened.payload()).isEqualTo(accountOpened().payload());

        BusinessEvent<AccountStatusChangedPayload> statusChanged = roundTrip(accountStatusChanged(),
                AccountStatusChangedPayload.class);
        assertThat(statusChanged.type()).isEqualTo("account.status.changed");
        assertThat(statusChanged.payload()).isEqualTo(accountStatusChanged().payload());

        BusinessEvent<DocumentUploadedPayload> uploaded = roundTrip(documentUploaded(),
                DocumentUploadedPayload.class);
        assertThat(uploaded.type()).isEqualTo("document.uploaded");
        assertThat(uploaded.payload()).isEqualTo(documentUploaded().payload());

        BusinessEvent<ExportRequestedPayload> requested = roundTrip(exportRequested(),
                ExportRequestedPayload.class);
        assertThat(requested.type()).isEqualTo("export.requested");
        assertThat(requested.payload()).isEqualTo(exportRequested().payload());

        BusinessEvent<StatementGeneratedPayload> generated = roundTrip(statementGenerated(),
                StatementGeneratedPayload.class);
        assertThat(generated.type()).isEqualTo("statement.generated");
        assertThat(generated.subjectType()).isEqualTo(SubjectType.ACCOUNT);
        assertThat(generated.payload()).isEqualTo(statementGenerated().payload());
    }

    @Test
    void writesTheDocumentedWireShape() throws Exception {
        String wire = json.toJson(customerRegistered());

        JsonNode node = new ObjectMapper().readTree(wire);
        assertThat(node.get("eventId").textValue()).isEqualTo(EVENT_ID.toString());
        assertThat(node.get("type").textValue()).isEqualTo("customer.registered");
        assertThat(node.get("version").isNumber()).isTrue();
        assertThat(node.get("version").intValue()).isEqualTo(1);
        assertThat(node.get("occurredAt").textValue()).isEqualTo("2025-03-04T09:07:45Z");
        assertThat(node.get("subjectType").textValue()).isEqualTo("CUSTOMER");
        assertThat(node.get("subjectId").longValue()).isEqualTo(1L);
        assertThat(node.get("causedBy").textValue()).isEqualTo(CAUSED_BY);
        assertThat(node.get("correlationId").textValue()).isEqualTo(CORRELATION_ID);

        JsonNode payload = node.get("payload");
        assertThat(payload.isObject()).isTrue();
        assertThat(payload.get("customerId").longValue()).isEqualTo(1L);
        assertThat(payload.get("firstName").textValue()).isEqualTo("John");
        assertThat(payload.get("lastName").textValue()).isEqualTo("Doe");
        assertThat(payload.get("email").textValue()).isEqualTo("john@example.com");

        assertThat(wire).contains("\"type\":\"customer.registered\"");
        assertThat(wire).contains("\"version\":1");
        assertThat(wire).contains("\"eventId\"", "\"occurredAt\"", "\"subjectType\"", "\"subjectId\"",
                "\"causedBy\"", "\"correlationId\"", "\"payload\"");
    }

    @Test
    void ignoresUnknownEnvelopeAndPayloadFields() {
        // A producer that already added an optional envelope field and an optional payload
        // field must not break this reader.
        String withUnknownFields = """
                {
                  "eventId": "3f1d2c9a-8b7e-4c6d-9f01-2a3b4c5d6e7f",
                  "type": "customer.registered",
                  "version": 1,
                  "occurredAt": "2025-03-04T09:07:45Z",
                  "subjectType": "CUSTOMER",
                  "subjectId": 1,
                  "causedBy": "ops.user",
                  "correlationId": "corr-2025-03-04-0001",
                  "tenantId": "bank-1",
                  "payload": {
                    "customerId": 1,
                    "firstName": "John",
                    "lastName": "Doe",
                    "email": "john@example.com",
                    "channel": "branch"
                  }
                }
                """;

        BusinessEvent<CustomerRegisteredPayload> event =
                json.fromJson(withUnknownFields, CustomerRegisteredPayload.class);

        assertThat(event.eventId()).isEqualTo(EVENT_ID);
        assertThat(event.type()).isEqualTo("customer.registered");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.subjectId()).isEqualTo(1L);
        assertThat(event.payload().customerId()).isEqualTo(1L);
        assertThat(event.payload().email()).isEqualTo("john@example.com");
    }

    @Test
    void keepsAnUnknownHigherVersionOnTheEnvelope() {
        // A version 2 producer, read by this v1-aware code: the envelope keeps 2 so a
        // consumer can branch on the version instead of failing.
        String versionTwo = """
                {
                  "eventId": "3f1d2c9a-8b7e-4c6d-9f01-2a3b4c5d6e7f",
                  "type": "customer.registered",
                  "version": 2,
                  "occurredAt": "2025-03-04T09:07:45Z",
                  "subjectType": "CUSTOMER",
                  "subjectId": 1,
                  "causedBy": "ops.user",
                  "correlationId": "corr-2025-03-04-0001",
                  "payload": {
                    "customerId": 1,
                    "firstName": "John",
                    "lastName": "Doe",
                    "email": "john@example.com"
                  }
                }
                """;

        BusinessEvent<CustomerRegisteredPayload> event =
                json.fromJson(versionTwo, CustomerRegisteredPayload.class);

        assertThat(event.version()).isEqualTo(2);
        assertThat(event.type()).isEqualTo("customer.registered");
        assertThat(event.payload().email()).isEqualTo("john@example.com");
    }

    @Test
    void anEnvelopeWithVersionZeroCannotBeWrittenAsJson() {
        assertThatThrownBy(() -> new BusinessEvent<>(EVENT_ID, "customer.registered", 0, OCCURRED_AT,
                SubjectType.CUSTOMER, 1L, CAUSED_BY, CORRELATION_ID,
                new CustomerRegisteredPayload(1L, "John", "Doe", "john@example.com")))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("version");
    }
}
