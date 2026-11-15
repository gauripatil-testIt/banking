package com.techverito.banking.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The documented event format, pinned by tests: the six types, the envelope fields,
 * the wire names, the rules a caller cannot get wrong, and forward compatibility
 * for a payload that carries a field this build does not know.
 */
class BusinessEventTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private BusinessEvent event(EventType type) {
        return BusinessEvent.create(type, 1L, null, "ops.user", "corr-123", CLOCK);
    }

    @Test
    void eventType_declaresExactlyTheSixDocumentedConstants() {
        assertThat(EventType.values()).containsExactly(
                EventType.CUSTOMER_REGISTERED,
                EventType.ACCOUNT_OPENED,
                EventType.ACCOUNT_STATUS_CHANGED,
                EventType.DOCUMENT_UPLOADED,
                EventType.EXPORT_REQUESTED,
                EventType.STATEMENT_GENERATED);

        assertThat(Arrays.stream(EventType.values()).map(Enum::name).toList()).containsExactly(
                "CUSTOMER_REGISTERED",
                "ACCOUNT_OPENED",
                "ACCOUNT_STATUS_CHANGED",
                "DOCUMENT_UPLOADED",
                "EXPORT_REQUESTED",
                "STATEMENT_GENERATED");
    }

    @Test
    void everyType_isSerialisedVerbatimAsItsConstantName() throws Exception {
        for (EventType type : EventType.values()) {
            JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(event(type)));

            assertThat(node.get("type").asText()).isEqualTo(type.name());
        }
    }

    @Test
    void payload_carriesTheWholeEnvelope() throws Exception {
        BusinessEvent event = BusinessEvent.create(EventType.DOCUMENT_UPLOADED, 7L, 9L, "ops.user",
                "corr-abc", CLOCK);

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(event));

        assertThat(node.get("eventId").asText()).isEqualTo(event.eventId());
        assertThat(node.get("type").asText()).isEqualTo("DOCUMENT_UPLOADED");
        assertThat(node.get("version").asInt()).isEqualTo(BusinessEvent.CURRENT_VERSION);
        assertThat(node.get("occurredAt").isNull()).isFalse();
        assertThat(node.get("customerId").isIntegralNumber()).isTrue();
        assertThat(node.get("customerId").asLong()).isEqualTo(7L);
        assertThat(node.get("accountId").isIntegralNumber()).isTrue();
        assertThat(node.get("accountId").asLong()).isEqualTo(9L);
        assertThat(node.get("actor").asText()).isEqualTo("ops.user");
        assertThat(node.get("correlationId").asText()).isEqualTo("corr-abc");
    }

    @Test
    void payload_carriesCustomerIdOrAccountIdIndividually() throws Exception {
        JsonNode customerOnly = objectMapper.readTree(
                objectMapper.writeValueAsString(BusinessEvent.create(EventType.CUSTOMER_REGISTERED,
                        3L, null, "ops.user", "corr-1", CLOCK)));
        assertThat(customerOnly.get("customerId").asLong()).isEqualTo(3L);
        assertThat(customerOnly.has("accountId")).isFalse();

        JsonNode accountOnly = objectMapper.readTree(
                objectMapper.writeValueAsString(BusinessEvent.create(EventType.ACCOUNT_OPENED,
                        null, 4L, "ops.user", "corr-2", CLOCK)));
        assertThat(accountOnly.get("accountId").asLong()).isEqualTo(4L);
        assertThat(accountOnly.has("customerId")).isFalse();
    }

    @Test
    void eventWithoutCustomerIdAndAccountId_isRejectedNamingBothFields() {
        assertThatThrownBy(() -> BusinessEvent.create(EventType.CUSTOMER_REGISTERED, null, null,
                "ops.user", "corr-123", CLOCK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("customerId")
                .hasMessageContaining("accountId");
    }

    @Test
    void blankEventIdActorAndCorrelationId_areRejected() {
        assertThatThrownBy(() -> new BusinessEvent(" ", EventType.CUSTOMER_REGISTERED, 1, NOW, 1L, null,
                "ops.user", "corr-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");

        assertThatThrownBy(() -> new BusinessEvent(UUID.randomUUID().toString(), EventType.CUSTOMER_REGISTERED,
                1, NOW, 1L, null, "  ", "corr-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actor");

        assertThatThrownBy(() -> new BusinessEvent(UUID.randomUUID().toString(), EventType.CUSTOMER_REGISTERED,
                1, NOW, 1L, null, "ops.user", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("correlationId");
    }

    @Test
    void versionBelowOneNullTypeAndNullOccurredAt_areRejected() {
        assertThatThrownBy(() -> new BusinessEvent(UUID.randomUUID().toString(), EventType.CUSTOMER_REGISTERED,
                0, NOW, 1L, null, "ops.user", "corr-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");

        assertThatThrownBy(() -> new BusinessEvent(UUID.randomUUID().toString(), null,
                1, NOW, 1L, null, "ops.user", "corr-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type");

        assertThatThrownBy(() -> new BusinessEvent(UUID.randomUUID().toString(), EventType.CUSTOMER_REGISTERED,
                1, null, 1L, null, "ops.user", "corr-123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurredAt");
    }

    @Test
    void unknownJsonField_isIgnoredAndDeclaredFieldsAreUnchanged() throws Exception {
        BusinessEvent published = event(EventType.CUSTOMER_REGISTERED);
        ObjectNode payload = objectMapper.valueToTree(published);
        payload.put("schemaOrigin", "banking");
        payload.put("someFutureField", 42);

        BusinessEvent consumed = objectMapper.treeToValue(payload, BusinessEvent.class);

        assertThat(consumed).isEqualTo(published);
        assertThat(consumed.eventId()).isEqualTo(published.eventId());
        assertThat(consumed.correlationId()).isEqualTo("corr-123");
    }

    @Test
    void versionIsCarriedInThePayloadSoAConsumerCanBranchOnIt() throws Exception {
        ObjectNode payload = objectMapper.valueToTree(event(EventType.ACCOUNT_OPENED));
        assertThat(payload.get("version").asInt()).isEqualTo(1);
        payload.put("version", 2);

        BusinessEvent consumed = objectMapper.treeToValue(payload, BusinessEvent.class);

        assertThat(consumed.version()).isEqualTo(2);
    }

    @Test
    void create_fillsIdTimeAndCurrentVersion() {
        BusinessEvent event = BusinessEvent.create(EventType.EXPORT_REQUESTED, 1L, null, "ops.user",
                "corr-123", CLOCK);

        assertThat(UUID.fromString(event.eventId())).isNotNull();
        assertThat(event.occurredAt()).isEqualTo(NOW);
        assertThat(event.version()).isEqualTo(BusinessEvent.CURRENT_VERSION);
        assertThat(BusinessEvent.CURRENT_VERSION).isEqualTo(1);
        assertThat(event.type()).isEqualTo(EventType.EXPORT_REQUESTED);
    }

    @Test
    void create_givesEveryEventItsOwnId() {
        List<BusinessEvent> events = List.of(
                event(EventType.STATEMENT_GENERATED),
                event(EventType.STATEMENT_GENERATED));

        assertThat(events.get(0).eventId()).isNotEqualTo(events.get(1).eventId());
    }
}
