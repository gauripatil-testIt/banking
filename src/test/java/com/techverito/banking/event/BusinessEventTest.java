package com.techverito.banking.event;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.DocumentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the one business event format and the versioning rule: an added payload
 * field keeps the version and existing consumers keep working, while a breaking
 * change is carried as a new payload version.
 */
class BusinessEventTest {

    private final ObjectMapper tolerantMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private CustomerRegisteredPayload customerRegisteredPayload() {
        return new CustomerRegisteredPayload(1L, "john@example.com", CustomerStatus.ACTIVE);
    }

    @Test
    void eventType_hasExactlyTheSixDocumentedConstants() {
        assertThat(EventType.values()).containsExactly(
                EventType.CUSTOMER_REGISTERED,
                EventType.ACCOUNT_OPENED,
                EventType.ACCOUNT_STATUS_CHANGED,
                EventType.DOCUMENT_UPLOADED,
                EventType.EXPORT_REQUESTED,
                EventType.STATEMENT_GENERATED);
    }

    @Test
    void of_stampsUniqueIdTypeVersionTimeAndSubject() {
        CustomerRegisteredPayload payload = customerRegisteredPayload();

        BusinessEvent first = BusinessEvent.of(payload, 1L, null);
        BusinessEvent second = BusinessEvent.of(payload, 1L, null);

        assertThat(first.eventId()).isNotBlank();
        assertThat(UUID.fromString(first.eventId()).toString()).isEqualTo(first.eventId());
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
        assertThat(first.type()).isEqualTo(EventType.CUSTOMER_REGISTERED);
        assertThat(first.version()).isEqualTo(1);
        assertThat(first.occurredAt()).isNotNull().isBeforeOrEqualTo(Instant.now());
        assertThat(first.customerId()).isEqualTo(1L);
        assertThat(first.accountId()).isNull();
        assertThat(first.payload()).isSameAs(payload);
        assertThat(first.actor()).isNull();
        assertThat(first.correlationId()).isNull();

        BusinessEvent accountEvent = BusinessEvent.of(
                new AccountOpenedPayload(5L, 1L, "ACC001", AccountType.SAVINGS, AccountStatus.ACTIVE), 1L, 5L);

        assertThat(accountEvent.type()).isEqualTo(EventType.ACCOUNT_OPENED);
        assertThat(accountEvent.customerId()).isEqualTo(1L);
        assertThat(accountEvent.accountId()).isEqualTo(5L);
    }

    @Test
    void of_rejectsNullPayloadOrNullType() {
        assertThatThrownBy(() -> BusinessEvent.of(null, 1L, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new BusinessEvent("event-id", null, 1, Instant.now(),
                1L, null, null, null, customerRegisteredPayload()))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new BusinessEvent("event-id", EventType.CUSTOMER_REGISTERED, 1, Instant.now(),
                1L, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payloadsReportVersionOne() {
        List<EventPayload> payloads = List.of(
                customerRegisteredPayload(),
                new AccountOpenedPayload(5L, 1L, "ACC001", AccountType.SAVINGS, AccountStatus.ACTIVE),
                new AccountStatusChangedPayload(5L, AccountStatus.ACTIVE, AccountStatus.CLOSED),
                new DocumentUploadedPayload(9L, DocumentType.ID_PROOF, "proof.pdf", 4L, "ops.user"),
                new ExportRequestedPayload(1L, "PDF"),
                new StatementGeneratedPayload(5L, "2024-05"));

        assertThat(payloads).extracting(EventPayload::version).containsOnly(1);

        for (EventPayload payload : payloads) {
            BusinessEvent event = BusinessEvent.of(payload, 1L, null);
            assertThat(event.version()).isEqualTo(payload.version()).isEqualTo(1);
            assertThat(event.type()).isEqualTo(payload.eventType());
        }
    }

    @Test
    void addedPayloadField_doesNotBreakExistingConsumer() throws Exception {
        CustomerRegisteredPayload payload = customerRegisteredPayload();

        ObjectNode withExtraField = (ObjectNode) tolerantMapper.readTree(tolerantMapper.writeValueAsString(payload));
        withExtraField.put("loyaltyTier", "GOLD");

        CustomerRegisteredPayload deserialised = tolerantMapper.treeToValue(withExtraField,
                CustomerRegisteredPayload.class);

        assertThat(deserialised).isEqualTo(payload);
        assertThat(deserialised.customerId()).isEqualTo(1L);
        assertThat(deserialised.email()).isEqualTo("john@example.com");
        assertThat(deserialised.status()).isEqualTo(CustomerStatus.ACTIVE);

        JsonNode knownFields = tolerantMapper.readTree(tolerantMapper.writeValueAsString(deserialised));
        assertThat(knownFields.get("customerId").asLong()).isEqualTo(1L);
        assertThat(knownFields.get("email").asText()).isEqualTo("john@example.com");
        assertThat(knownFields.get("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void higherPayloadVersion_isCarriedOnTheEnvelope() {
        BusinessEvent versionTwo = BusinessEvent.of(new VersionTwoTestPayload("reshaped"), 1L, null);
        BusinessEvent versionOne = BusinessEvent.of(customerRegisteredPayload(), 1L, null);

        assertThat(versionTwo.version()).isEqualTo(2);
        assertThat(versionTwo.type()).isEqualTo(EventType.CUSTOMER_REGISTERED);
        assertThat(versionOne.version()).isEqualTo(1);
    }

    /**
     * Stands in for a payload whose shape changed in a backwards incompatible way:
     * the change ships as version 2 instead of mutating the existing payload.
     */
    private record VersionTwoTestPayload(String description) implements EventPayload {

        @Override
        public EventType eventType() {
            return EventType.CUSTOMER_REGISTERED;
        }

        @Override
        public int version() {
            return 2;
        }
    }
}
