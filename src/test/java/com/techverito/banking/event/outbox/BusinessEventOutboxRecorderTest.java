package com.techverito.banking.event.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.techverito.banking.entity.BusinessEventOutboxEntry;
import com.techverito.banking.entity.OutboxEventStatus;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.EventType;
import com.techverito.banking.repository.BusinessEventOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a business action stores in the transactional outbox, against the real database
 * the application runs on (H2 in the tests, the entity mapping that Oracle gets as DDL).
 * No broker is involved: the recorder never talks to one, which is what lets a customer
 * action commit while Kafka is down.
 *
 * <p>The test is deliberately non-transactional, so the rows it writes are committed the
 * way a business transaction commits them, the unique constraint on {@code event_id} is
 * enforced by the database rather than by a rollback, and a rolled-back business
 * transaction can be shown to leave nothing behind.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BusinessEventOutboxRecorderTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    @Autowired
    BusinessEventOutboxRepository outboxRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** The mapper the application uses: ISO-8601 instants, no numeric timestamps. */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private BusinessEventOutboxRecorder recorder;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        RequestContextHolder.resetRequestAttributes();
        recorder = new BusinessEventOutboxRecorder(outboxRepository, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC), new EventActorContext());
    }

    /** The stored rows in the order the drain takes them. */
    private List<BusinessEventOutboxEntry> storedRows() {
        return outboxRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    /** A second entry for an event that is already stored: same event id, fresh row. */
    private BusinessEventOutboxEntry secondRowFor(BusinessEvent event) {
        try {
            return BusinessEventOutboxEntry.builder()
                    .eventId(event.eventId())
                    .eventType(event.type())
                    .eventVersion(event.version())
                    .occurredAt(event.occurredAt())
                    .customerId(event.customerId())
                    .accountId(event.accountId())
                    .actor(event.actor())
                    .correlationId(event.correlationId())
                    .businessKey(BusinessEventOutboxRecorder.businessKey(event.customerId(), event.accountId()))
                    .payload(objectMapper.writeValueAsString(event))
                    .status(OutboxEventStatus.PENDING)
                    .attempts(0)
                    .nextAttemptAt(NOW)
                    .createdAt(NOW)
                    .build();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void record_customerRegistered_storesOnePendingRowCarryingTheWholeEnvelope() throws Exception {
        BusinessEvent recorded = recorder.record(EventType.CUSTOMER_REGISTERED, 1L, null);

        List<BusinessEventOutboxEntry> rows = storedRows();
        assertThat(rows).hasSize(1);

        BusinessEventOutboxEntry row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getEventId()).isEqualTo(recorded.eventId());
        assertThat(row.getEventType()).isEqualTo(EventType.CUSTOMER_REGISTERED);
        assertThat(row.getEventVersion()).isEqualTo(BusinessEvent.CURRENT_VERSION);
        assertThat(row.getOccurredAt()).isEqualTo(NOW);
        assertThat(row.getCustomerId()).isEqualTo(1L);
        assertThat(row.getAccountId()).isNull();
        assertThat(row.getActor()).isEqualTo(EventActorContext.DEFAULT_ACTOR);
        assertThat(row.getCorrelationId()).isNotBlank();
        assertThat(row.getBusinessKey()).isEqualTo("customer:1");
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getDeadLetteredAt()).isNull();

        JsonNode payload = objectMapper.readTree(row.getPayload());
        assertThat(payload.get("eventId").asText()).isEqualTo(recorded.eventId());
        assertThat(payload.get("type").asText()).isEqualTo("CUSTOMER_REGISTERED");
        assertThat(payload.get("version").asInt()).isEqualTo(BusinessEvent.CURRENT_VERSION);
        assertThat(payload.get("occurredAt").asText()).isEqualTo("2025-03-04T09:07:45Z");
        assertThat(payload.get("customerId").asLong()).isEqualTo(1L);
        assertThat(payload.has("accountId")).isFalse();
        assertThat(payload.get("actor").asText()).isEqualTo(EventActorContext.DEFAULT_ACTOR);
        assertThat(payload.get("correlationId").asText()).isEqualTo(recorded.correlationId());

        assertThat(objectMapper.readValue(row.getPayload(), BusinessEvent.class)).isEqualTo(recorded);
    }

    @Test
    void record_accountOpened_storesBothIdsAndIsKeyedByTheAccount() throws Exception {
        BusinessEvent recorded = recorder.record(EventType.ACCOUNT_OPENED, 5L, 9L);

        BusinessEventOutboxEntry row = storedRows().get(0);
        assertThat(row.getEventType()).isEqualTo(EventType.ACCOUNT_OPENED);
        assertThat(row.getCustomerId()).isEqualTo(5L);
        assertThat(row.getAccountId()).isEqualTo(9L);
        assertThat(row.getBusinessKey()).isEqualTo("account:9");

        BusinessEvent readBack = objectMapper.readValue(row.getPayload(), BusinessEvent.class);
        assertThat(readBack.eventId()).isEqualTo(recorded.eventId());
        assertThat(readBack.type()).isEqualTo(EventType.ACCOUNT_OPENED);
        assertThat(readBack.occurredAt()).isEqualTo(NOW);
        assertThat(readBack.customerId()).isEqualTo(5L);
        assertThat(readBack.accountId()).isEqualTo(9L);
        assertThat(readBack.actor()).isEqualTo(recorded.actor());
        assertThat(readBack.correlationId()).isEqualTo(recorded.correlationId());
    }

    @Test
    void record_storesOnePendingRowPerEventTypeKeyedByWhatItConcerns() {
        recorder.record(EventType.CUSTOMER_REGISTERED, 1L, null);
        recorder.record(EventType.ACCOUNT_OPENED, 1L, 9L);
        recorder.record(EventType.ACCOUNT_STATUS_CHANGED, 1L, 9L);
        recorder.record(EventType.DOCUMENT_UPLOADED, 1L, null);
        recorder.record(EventType.EXPORT_REQUESTED, 1L, null);

        List<BusinessEventOutboxEntry> rows = storedRows();

        assertThat(rows).extracting(BusinessEventOutboxEntry::getEventType).containsExactly(
                EventType.CUSTOMER_REGISTERED,
                EventType.ACCOUNT_OPENED,
                EventType.ACCOUNT_STATUS_CHANGED,
                EventType.DOCUMENT_UPLOADED,
                EventType.EXPORT_REQUESTED);
        assertThat(rows).extracting(BusinessEventOutboxEntry::getBusinessKey).containsExactly(
                "customer:1", "account:9", "account:9", "customer:1", "customer:1");
        assertThat(rows).extracting(BusinessEventOutboxEntry::getStatus)
                .containsOnly(OutboxEventStatus.PENDING);
        assertThat(rows).extracting(BusinessEventOutboxEntry::getEventId).doesNotHaveDuplicates();
    }

    @Test
    void record_takesTheActorAndTheCorrelationIdFromTheRequestHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Actor", "ops.user");
        request.addHeader("X-Correlation-Id", "corr-bank-7-document");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        BusinessEvent recorded = recorder.record(EventType.DOCUMENT_UPLOADED, 7L, null);

        BusinessEventOutboxEntry row = storedRows().get(0);
        assertThat(recorded.actor()).isEqualTo("ops.user");
        assertThat(recorded.correlationId()).isEqualTo("corr-bank-7-document");
        assertThat(row.getActor()).isEqualTo("ops.user");
        assertThat(row.getCorrelationId()).isEqualTo("corr-bank-7-document");
        assertThat(row.getBusinessKey()).isEqualTo("customer:7");
    }

    @Test
    void record_theSameEventIdTwice_failsOnTheUniqueConstraintSoNoSecondCopyIsStored() {
        BusinessEvent recorded = recorder.record(EventType.CUSTOMER_REGISTERED, 1L, null);

        assertThatThrownBy(() -> outboxRepository.saveAndFlush(secondRowFor(recorded)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(storedRows()).hasSize(1);
        assertThat(outboxRepository.existsByEventId(recorded.eventId())).isTrue();
    }

    @Test
    void record_leavesNoRowWhenTheBusinessTransactionRollsBack() {
        TransactionTemplate businessTransaction = new TransactionTemplate(transactionManager);
        businessTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        businessTransaction.executeWithoutResult(status -> {
            BusinessEvent recorded = recorder.record(EventType.CUSTOMER_REGISTERED, 1L, null);
            outboxRepository.flush();
            assertThat(outboxRepository.existsByEventId(recorded.eventId())).isTrue();
            status.setRollbackOnly();
        });

        assertThat(outboxRepository.count()).isZero();
    }
}
