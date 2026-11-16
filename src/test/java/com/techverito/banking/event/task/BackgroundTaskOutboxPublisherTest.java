package com.techverito.banking.event.task;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.techverito.banking.config.BackgroundTaskProperties;
import com.techverito.banking.entity.BackgroundTaskOutboxEntry;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.event.outbox.EventActorContext;
import com.techverito.banking.repository.BackgroundTaskOutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The scheduled background-task drain against the real database and a Mockito double of
 * {@link BackgroundTaskPublisher}, so a test can decide which publishes fail. That is how
 * the confirmation failure, the retry, the independent draining of the different task
 * types and the exhausted-attempts outcome become assertions instead of hopes. No broker
 * and no Docker are involved; the real-broker proof is
 * {@code BackgroundTaskRabbitContainerTest}.
 *
 * <p>The test is non-transactional, so every {@code save} the drain does is committed the
 * way the application commits it, and the clock is movable so a row that is not yet due
 * can be shown to wait.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BackgroundTaskOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private static final int MAX_ATTEMPTS = 3;

    private static final String CONFIRMATION_FAILURE = "the broker did not confirm the record";

    @Autowired
    BackgroundTaskOutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final MutableClock clock = new MutableClock(NOW);

    private final BackgroundTaskPublisher taskPublisher = mock(BackgroundTaskPublisher.class);

    private final BackgroundTaskProperties properties = new BackgroundTaskProperties(
            "banking.background-tasks", MAX_ATTEMPTS, RETRY_DELAY, 200,
            Duration.ofSeconds(1), Duration.ofSeconds(30));

    private BackgroundTaskOutboxRecorder recorder;

    private BackgroundTaskOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        clock.reset(NOW);
        recorder = new BackgroundTaskOutboxRecorder(outboxRepository, objectMapper, clock, new EventActorContext());
        publisher = new BackgroundTaskOutboxPublisher(outboxRepository, taskPublisher, objectMapper, properties, clock);
    }

    /** The stored rows in the order the drain takes them. */
    private List<BackgroundTaskOutboxEntry> storedRows() {
        return outboxRepository.findAll(Sort.by(Sort.Direction.ASC, "id"));
    }

    /** The stored row of one task type, which is the row a task of that type is published from. */
    private BackgroundTaskOutboxEntry rowOf(TaskType type) {
        return storedRows().stream()
                .filter(row -> row.getTaskType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No background task row of type " + type));
    }

    /** A failure as the RabbitMQ adapter reports it: the broker did not confirm the task. */
    private TaskPublishException confirmationFailure(String taskId) {
        return new TaskPublishException(taskId, CONFIRMATION_FAILURE,
                new IllegalStateException("the broker is unreachable"));
    }

    @Test
    void drain_publishesTheStoredMessageAndMarksTheRowPublishedOnlyThen() throws Exception {
        BackgroundTaskMessage recorded = recorder.submit(TaskType.WELCOME_EMAIL, "customer", 1L, 1L);

        publisher.drain();

        ArgumentCaptor<BackgroundTaskMessage> published = ArgumentCaptor.forClass(BackgroundTaskMessage.class);
        verify(taskPublisher).publish(published.capture());
        assertThat(published.getValue()).isEqualTo(recorded);

        List<BackgroundTaskOutboxEntry> rows = storedRows();
        assertThat(rows).hasSize(1);
        BackgroundTaskOutboxEntry row = rows.get(0);
        assertThat(row.getStatus()).isEqualTo(BackgroundTaskStatus.PUBLISHED);
        assertThat(row.getPublishedAt()).isEqualTo(NOW);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();

        // The drain publishes the payload stored on the row, not a recomputed message.
        assertThat(objectMapper.readValue(row.getPayload(), BackgroundTaskMessage.class))
                .isEqualTo(published.getValue());
    }

    @Test
    void drain_keepsAFailedTaskPendingWithItsReasonAndSubmitsItOnALaterDrain() {
        BackgroundTaskMessage recorded = recorder.submit(TaskType.STATEMENT_GENERATION, "account", 9L, 5L);
        doThrow(confirmationFailure(recorded.taskId())).doNothing()
                .when(taskPublisher).publish(any());

        publisher.drain();

        BackgroundTaskOutboxEntry failedRow = storedRows().get(0);
        assertThat(failedRow.getStatus()).isEqualTo(BackgroundTaskStatus.PENDING);
        assertThat(failedRow.getAttempts()).isEqualTo(1);
        assertThat(failedRow.getLastError())
                .contains(recorded.taskId())
                .contains(CONFIRMATION_FAILURE);
        assertThat(failedRow.getNextAttemptAt()).isEqualTo(NOW.plus(RETRY_DELAY));
        assertThat(failedRow.getPublishedAt()).isNull();

        // Not due yet, so the next drain leaves it alone.
        publisher.drain();
        assertThat(storedRows().get(0).getAttempts()).isEqualTo(1);
        verify(taskPublisher, times(1)).publish(any());

        clock.advance(RETRY_DELAY);
        publisher.drain();

        BackgroundTaskOutboxEntry publishedRow = storedRows().get(0);
        assertThat(publishedRow.getStatus()).isEqualTo(BackgroundTaskStatus.PUBLISHED);
        assertThat(publishedRow.getPublishedAt()).isEqualTo(clock.instant());
        assertThat(publishedRow.getLastError()).isNull();
        verify(taskPublisher, times(2)).publish(any());
    }

    @Test
    void drain_submitsTheOtherTaskTypesEvenWhenOneTaskFails() {
        BackgroundTaskMessage failing = recorder.submit(TaskType.WELCOME_EMAIL, "customer", 1L, 1L);
        BackgroundTaskMessage published = recorder.submit(TaskType.DOCUMENT_SCAN, "document", 4L, 1L);
        doThrow(confirmationFailure(failing.taskId()))
                .when(taskPublisher).publish(argThat(task -> task != null && task.type() == TaskType.WELCOME_EMAIL));

        publisher.drain();

        // The other task of the same drain was still submitted, unchanged.
        verify(taskPublisher).publish(published);

        BackgroundTaskOutboxEntry failingRow = rowOf(TaskType.WELCOME_EMAIL);
        assertThat(failingRow.getStatus()).isEqualTo(BackgroundTaskStatus.PENDING);
        assertThat(failingRow.getAttempts()).isEqualTo(1);
        assertThat(failingRow.getLastError()).contains(CONFIRMATION_FAILURE);

        BackgroundTaskOutboxEntry publishedRow = rowOf(TaskType.DOCUMENT_SCAN);
        assertThat(publishedRow.getStatus()).isEqualTo(BackgroundTaskStatus.PUBLISHED);
        assertThat(publishedRow.getPublishedAt()).isEqualTo(NOW);
    }

    @Test
    void drain_neverPublishesAnAlreadyPublishedTaskAgain() {
        recorder.submit(TaskType.WELCOME_EMAIL, "customer", 1L, 1L);

        publisher.drain();
        publisher.drain();
        clock.advance(RETRY_DELAY);
        publisher.drain();

        verify(taskPublisher, times(1)).publish(any());
        assertThat(storedRows()).hasSize(1);
        assertThat(storedRows().get(0).getStatus()).isEqualTo(BackgroundTaskStatus.PUBLISHED);
    }

    @Test
    void drain_keepsTheRowFailedAndReportsItOnceOnceAttemptsAreExhausted() {
        BackgroundTaskMessage recorded = recorder.submit(TaskType.DOCUMENT_EXPORT, "data_export", 1L, 1L);
        doThrow(confirmationFailure(recorded.taskId())).when(taskPublisher).publish(any());

        Logger logger = (Logger) LoggerFactory.getLogger(BackgroundTaskOutboxPublisher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                publisher.drain();
                if (attempt < MAX_ATTEMPTS) {
                    clock.advance(RETRY_DELAY);
                }
            }
            // A FAILED row is not PENDING, so it is never selected again.
            publisher.drain();

            List<BackgroundTaskOutboxEntry> rows = storedRows();
            assertThat(rows).hasSize(1);
            BackgroundTaskOutboxEntry failedRow = rows.get(0);
            assertThat(failedRow.getTaskId()).isEqualTo(recorded.taskId());
            assertThat(failedRow.getStatus()).isEqualTo(BackgroundTaskStatus.FAILED);
            assertThat(failedRow.getAttempts()).isEqualTo(MAX_ATTEMPTS);
            assertThat(failedRow.getLastError()).contains(CONFIRMATION_FAILURE);
            assertThat(failedRow.getPublishedAt()).isNull();

            List<ILoggingEvent> errors = appender.list.stream()
                    .filter(event -> event.getLevel() == Level.ERROR)
                    .toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getFormattedMessage())
                    .contains(recorded.taskId())
                    .contains("DOCUMENT_EXPORT")
                    .contains(CONFIRMATION_FAILURE);

            verify(taskPublisher, times(MAX_ATTEMPTS)).publish(any());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /** A clock a test can move forward, so "not due yet" and "due on the retry" are checkable. */
    private static final class MutableClock extends Clock {

        private final ZoneId zone = ZoneOffset.UTC;

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        void reset(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
