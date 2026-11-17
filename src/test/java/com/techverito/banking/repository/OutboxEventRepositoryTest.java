package com.techverito.banking.repository;

import com.techverito.banking.entity.OutboxEvent;
import com.techverito.banking.entity.OutboxEventStatus;
import com.techverito.banking.event.SubjectType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The outbox row as the database sees it: the JSON payload survives a round trip unchanged, the
 * relay query only ever returns due PENDING rows in per-subject sequence order, and a FAILED row
 * stays behind with its reason and attempt count.
 */
@DataJpaTest
class OutboxEventRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2025-03-04T09:07:45Z");

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    TestEntityManager entityManager;

    private static OutboxEvent pendingRow(UUID eventId, SubjectType subjectType, Long subjectId, long sequenceNo) {
        return OutboxEvent.builder()
                .eventId(eventId)
                .eventType("account.status.changed")
                .eventVersion(1)
                .subjectType(subjectType)
                .subjectId(subjectId)
                .sequenceNo(sequenceNo)
                .orderingKey(subjectType.name() + "-" + subjectId)
                .topic("banking.events")
                .payload("{\"eventId\":\"" + eventId + "\",\"type\":\"account.status.changed\"}")
                .createdAt(CREATED_AT)
                .nextAttemptAt(CREATED_AT)
                .build();
    }

    @Test
    void save_thenReadBack_keepsALargeJsonPayloadByteIdentical() {
        String payload = "{\"type\":\"customer.registered\",\"familyName\":\""
                + "Übersicht-".repeat(10_000) + "\"}";
        OutboxEvent saved = outboxEventRepository.save(OutboxEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("customer.registered")
                .eventVersion(1)
                .subjectType(SubjectType.CUSTOMER)
                .subjectId(1L)
                .sequenceNo(1L)
                .orderingKey("CUSTOMER-1")
                .topic("banking.events")
                .payload(payload)
                .createdAt(CREATED_AT)
                .nextAttemptAt(CREATED_AT)
                .build());
        outboxEventRepository.flush();
        Long id = saved.getId();
        entityManager.clear();

        OutboxEvent reloaded = outboxEventRepository.findById(id).orElseThrow();

        assertThat(reloaded.getPayload()).isEqualTo(payload);
        assertThat(reloaded.getEventId()).isEqualTo(saved.getEventId());
        assertThat(reloaded.getEventType()).isEqualTo("customer.registered");
        assertThat(reloaded.getEventVersion()).isEqualTo(1);
        assertThat(reloaded.getSubjectType()).isEqualTo(SubjectType.CUSTOMER);
        assertThat(reloaded.getSubjectId()).isEqualTo(1L);
        assertThat(reloaded.getOrderingKey()).isEqualTo("CUSTOMER-1");
        assertThat(reloaded.getTopic()).isEqualTo("banking.events");
        assertThat(reloaded.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
    }

    @Test
    void findPendingForRelay_returnsDueRowsByOrderingKeyThenSequence() {
        UUID accountSecond = UUID.randomUUID();
        outboxEventRepository.save(pendingRow(accountSecond, SubjectType.ACCOUNT, 2L, 1L));
        UUID accountFirst = UUID.randomUUID();
        outboxEventRepository.save(pendingRow(accountFirst, SubjectType.ACCOUNT, 1L, 2L));
        UUID accountFirstEarlier = UUID.randomUUID();
        outboxEventRepository.save(pendingRow(accountFirstEarlier, SubjectType.ACCOUNT, 1L, 1L));
        outboxEventRepository.flush();
        entityManager.clear();

        List<OutboxEvent> pending = outboxEventRepository.findPendingForRelay(CREATED_AT);

        assertThat(pending).extracting(OutboxEvent::getOrderingKey)
                .containsExactly("ACCOUNT-1", "ACCOUNT-1", "ACCOUNT-2");
        assertThat(pending).extracting(OutboxEvent::getEventId)
                .containsExactly(accountFirstEarlier, accountFirst, accountSecond);
        assertThat(outboxEventRepository.findPendingByOrderingKey("ACCOUNT-1"))
                .extracting(OutboxEvent::getEventId)
                .containsExactly(accountFirstEarlier, accountFirst);
    }

    @Test
    void findPendingForRelay_skipsARowThatIsNotDueYetAndNeverReturnsAFailedRow() {
        OutboxEvent waiting = pendingRow(UUID.randomUUID(), SubjectType.ACCOUNT, 5L, 1L);
        waiting.setNextAttemptAt(CREATED_AT.plusSeconds(300));
        outboxEventRepository.save(waiting);
        OutboxEvent failed = pendingRow(UUID.randomUUID(), SubjectType.ACCOUNT, 6L, 1L);
        failed.recordFailedAttempt("the topic name is not valid", CREATED_AT);
        failed.markUnpublishable("the topic name is not valid");
        outboxEventRepository.save(failed);
        UUID due = UUID.randomUUID();
        outboxEventRepository.save(pendingRow(due, SubjectType.ACCOUNT, 7L, 1L));
        outboxEventRepository.flush();
        entityManager.clear();

        List<OutboxEvent> pending = outboxEventRepository.findPendingForRelay(CREATED_AT);

        assertThat(pending).extracting(OutboxEvent::getEventId).containsExactly(due);
        assertThat(outboxEventRepository.findPendingByOrderingKey("ACCOUNT-6")).isEmpty();
    }

    @Test
    void aFailedRowKeepsItsReasonAndAttemptCountForInvestigation() {
        OutboxEvent failed = pendingRow(UUID.randomUUID(), SubjectType.CUSTOMER, 3L, 1L);
        failed.recordFailedAttempt("broker rejected the record", CREATED_AT.plusSeconds(5));
        failed.markUnpublishable("broker rejected the record");
        outboxEventRepository.save(failed);
        outboxEventRepository.flush();
        UUID eventId = failed.getEventId();
        entityManager.clear();

        List<OutboxEvent> kept = outboxEventRepository.findFailed();

        assertThat(kept).hasSize(1);
        OutboxEvent reloaded = kept.get(0);
        assertThat(reloaded.getEventId()).isEqualTo(eventId);
        assertThat(reloaded.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(reloaded.getFailureReason()).isEqualTo("broker rejected the record");
        assertThat(reloaded.getAttemptCount()).isEqualTo(1);
        assertThat(outboxEventRepository.findByStatus(OutboxEventStatus.FAILED)).hasSize(1);
        assertThat(outboxEventRepository.findByStatus(OutboxEventStatus.PENDING)).isEmpty();
    }

    @Test
    void nextSequenceNo_startsAtOneAndKeepsTheOrderOfEachOrderingKey() {
        outboxEventRepository.save(pendingRow(UUID.randomUUID(), SubjectType.ACCOUNT, 42L, 1L));
        outboxEventRepository.save(pendingRow(UUID.randomUUID(), SubjectType.ACCOUNT, 42L, 2L));
        outboxEventRepository.save(pendingRow(UUID.randomUUID(), SubjectType.ACCOUNT, 43L, 9L));
        outboxEventRepository.flush();

        assertThat(outboxEventRepository.nextSequenceNo("ACCOUNT-42")).isEqualTo(3L);
        assertThat(outboxEventRepository.nextSequenceNo("ACCOUNT-43")).isEqualTo(10L);
        assertThat(outboxEventRepository.nextSequenceNo("ACCOUNT-44")).isEqualTo(1L);
    }
}
