package com.techverito.banking.repository;

import com.techverito.banking.entity.BackgroundTask;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.entity.BackgroundTaskType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox row itself: the unique business key that makes a repeated submission a
 * no-op, and the two queries the relay uses to pick the rows it should publish.
 */
@DataJpaTest
class BackgroundTaskRepositoryTest {

    private static final Instant CREATED_AT = Instant.parse("2025-03-04T09:07:45Z");

    private static final String EVENT_ID = "3f1d2c9a-8b7e-4c6d-9f01-2a3b4c5d6e7f";

    @Autowired
    BackgroundTaskRepository backgroundTaskRepository;

    @Autowired
    TestEntityManager entityManager;

    private BackgroundTask task(BackgroundTaskType taskType, String businessKey, Instant createdAt) {
        return BackgroundTask.builder()
                .taskType(taskType)
                .businessKey(businessKey)
                .status(BackgroundTaskStatus.PENDING)
                .eventId(EVENT_ID)
                .correlationId("corr-" + businessKey)
                .requestedBy("ops.user")
                .messageJson("{\"eventId\":\"" + EVENT_ID + "\",\"type\":\"" + taskType.eventType().getWireValue()
                        + "\",\"version\":1,\"payload\":{}}")
                .createdAt(createdAt)
                .build();
    }

    @Test
    void save_thenReadBack_roundTripsTheOutboxRowAsPendingWithNoAttempts() {
        BackgroundTask saved = backgroundTaskRepository.save(task(BackgroundTaskType.DOCUMENT_SCAN, "7", CREATED_AT));
        backgroundTaskRepository.flush();

        Long id = saved.getId();
        entityManager.clear();

        Optional<BackgroundTask> reloaded = backgroundTaskRepository.findById(id);

        assertThat(reloaded).isPresent();
        BackgroundTask found = reloaded.orElseThrow();
        assertThat(found.getTaskType()).isEqualTo(BackgroundTaskType.DOCUMENT_SCAN);
        assertThat(found.getBusinessKey()).isEqualTo("7");
        assertThat(found.getStatus()).isEqualTo(BackgroundTaskStatus.PENDING);
        assertThat(found.getEventId()).isEqualTo(EVENT_ID);
        assertThat(found.getCorrelationId()).isEqualTo("corr-7");
        assertThat(found.getRequestedBy()).isEqualTo("ops.user");
        assertThat(found.getMessageJson()).contains("\"type\":\"document.scan.requested\"");
        assertThat(found.getAttempts()).isZero();
        assertThat(found.getNextAttemptAt()).isNull();
        assertThat(found.getPublishedAt()).isNull();
        assertThat(found.getCreatedAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void findByTaskTypeAndBusinessKey_returnsTheSubmittedTask() {
        backgroundTaskRepository.saveAndFlush(task(BackgroundTaskType.DOCUMENT_SCAN, "7", CREATED_AT));

        assertThat(backgroundTaskRepository.findByTaskTypeAndBusinessKey(BackgroundTaskType.DOCUMENT_SCAN, "7"))
                .isPresent()
                .get()
                .extracting(BackgroundTask::getBusinessKey)
                .isEqualTo("7");
    }

    @Test
    void findByTaskTypeAndBusinessKey_isEmptyForAKeyThatWasNeverSubmitted() {
        backgroundTaskRepository.saveAndFlush(task(BackgroundTaskType.DOCUMENT_SCAN, "7", CREATED_AT));

        assertThat(backgroundTaskRepository.findByTaskTypeAndBusinessKey(BackgroundTaskType.DOCUMENT_SCAN, "8"))
                .isEmpty();
    }

    @Test
    void theSameTaskTypeAndBusinessKeyTwice_isRejectedByTheUniqueConstraint() {
        backgroundTaskRepository.saveAndFlush(task(BackgroundTaskType.DOCUMENT_SCAN, "7", CREATED_AT));

        assertThatThrownBy(() -> backgroundTaskRepository.saveAndFlush(
                task(BackgroundTaskType.DOCUMENT_SCAN, "7", CREATED_AT.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameBusinessKeyUnderADifferentTaskType_isAccepted() {
        backgroundTaskRepository.saveAndFlush(task(BackgroundTaskType.DOCUMENT_SCAN, "7", CREATED_AT));
        backgroundTaskRepository.saveAndFlush(task(BackgroundTaskType.DOCUMENT_EXPORT, "7", CREATED_AT.plusSeconds(1)));

        assertThat(backgroundTaskRepository.findByTaskTypeAndBusinessKey(BackgroundTaskType.DOCUMENT_SCAN, "7"))
                .isPresent();
        assertThat(backgroundTaskRepository.findByTaskTypeAndBusinessKey(BackgroundTaskType.DOCUMENT_EXPORT, "7"))
                .isPresent();
        assertThat(backgroundTaskRepository.count()).isEqualTo(2L);
    }

    @Test
    void failedAttemptsThatAreDue_areReturnedOldestFirstAndFutureOnesAreNot() {
        Instant now = Instant.parse("2025-03-04T10:00:00Z");
        BackgroundTask oldestDue = task(BackgroundTaskType.WELCOME_EMAIL, "1", CREATED_AT);
        oldestDue.setAttempts(1);
        oldestDue.setNextAttemptAt(now.minusSeconds(30));
        BackgroundTask newerDue = task(BackgroundTaskType.WELCOME_EMAIL, "2", CREATED_AT.plusSeconds(5));
        newerDue.setAttempts(2);
        newerDue.setNextAttemptAt(now.minusSeconds(1));
        BackgroundTask notDueYet = task(BackgroundTaskType.WELCOME_EMAIL, "3", CREATED_AT.plusSeconds(10));
        notDueYet.setAttempts(1);
        notDueYet.setNextAttemptAt(now.plusSeconds(3600));
        BackgroundTask neverAttempted = task(BackgroundTaskType.WELCOME_EMAIL, "4", CREATED_AT.plusSeconds(15));
        backgroundTaskRepository.saveAll(List.of(oldestDue, newerDue, notDueYet, neverAttempted));
        backgroundTaskRepository.flush();

        List<BackgroundTask> due = backgroundTaskRepository
                .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(BackgroundTaskStatus.PENDING, now);

        assertThat(due).extracting(BackgroundTask::getBusinessKey).containsExactly("1", "2");
        assertThat(due).extracting(BackgroundTask::getCreatedAt)
                .containsExactly(CREATED_AT, CREATED_AT.plusSeconds(5));
    }

    @Test
    void neverAttemptedPendingRows_areReturnedAndSubmittedOnesAreNot() {
        backgroundTaskRepository.saveAndFlush(task(BackgroundTaskType.WELCOME_EMAIL, "1", CREATED_AT));

        BackgroundTask alreadySubmitted = task(BackgroundTaskType.WELCOME_EMAIL, "2", CREATED_AT.plusSeconds(1));
        alreadySubmitted.setStatus(BackgroundTaskStatus.SUBMITTED);
        alreadySubmitted.setPublishedAt(CREATED_AT.plusSeconds(2));
        backgroundTaskRepository.saveAndFlush(alreadySubmitted);

        List<BackgroundTask> pending = backgroundTaskRepository
                .findTop50ByStatusAndNextAttemptAtIsNullOrderByCreatedAtAsc(BackgroundTaskStatus.PENDING);

        assertThat(pending).extracting(BackgroundTask::getBusinessKey).containsExactly("1");
    }

    @Test
    void dueRows_areCappedAtFiftyAndStartWithTheOldest() {
        Instant now = Instant.parse("2025-03-04T10:00:00Z");
        List<BackgroundTask> rows = new ArrayList<>();
        for (int i = 0; i < 55; i++) {
            BackgroundTask row = task(BackgroundTaskType.DOCUMENT_EXPORT, "key-" + i, CREATED_AT.plusSeconds(i));
            row.setNextAttemptAt(now.minusSeconds(60));
            rows.add(row);
        }
        backgroundTaskRepository.saveAll(rows);
        backgroundTaskRepository.flush();

        List<BackgroundTask> due = backgroundTaskRepository
                .findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(BackgroundTaskStatus.PENDING, now);

        assertThat(due).hasSize(50);
        assertThat(due.get(0).getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(due.get(49).getCreatedAt()).isEqualTo(CREATED_AT.plusSeconds(49));
    }
}
