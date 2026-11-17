package com.techverito.banking.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.techverito.banking.config.TaskQueueProperties;
import com.techverito.banking.entity.BackgroundTask;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.entity.BackgroundTaskType;
import com.techverito.banking.event.TaskQueuePublisher;
import com.techverito.banking.exception.TaskPublishException;
import com.techverito.banking.repository.BackgroundTaskRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled relay against a real outbox table: a task becomes SUBMITTED only when the
 * publisher (and therefore the broker) confirmed it, a failed publish keeps the row PENDING
 * with its retry state advanced, a confirmed row is never published twice, and a row that
 * the submitting transaction has not committed is invisible to the relay.
 */
@DataJpaTest
class TaskOutboxRelayTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final Instant CREATED_AT = Instant.parse("2025-03-04T09:00:00Z");

    private static final String EVENT_ID = "3f1d2c9a-8b7e-4c6d-9f01-2a3b4c5d6e7f";

    private static final String CORRELATION_ID = "corr-2025-03-04-0001";

    private static final TaskQueueProperties PROPERTIES = new TaskQueueProperties(
            "banking.tasks",
            "banking.tasks.welcome-email",
            "banking.tasks.statement-generation",
            "banking.tasks.document-export",
            "banking.tasks.document-scan",
            "task.welcome-email",
            "task.statement-generation",
            "task.document-export",
            "task.document-scan",
            Duration.ofSeconds(2),
            true);

    @Autowired
    BackgroundTaskRepository backgroundTaskRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final RecordingPublisher publisher = new RecordingPublisher();

    /** The relay under test, stamped with a fixed time so the backoff is predictable. */
    private TaskOutboxRelay relayAt(Instant now) {
        return new TaskOutboxRelay(backgroundTaskRepository, publisher, PROPERTIES,
                Clock.fixed(now, ZoneOffset.UTC));
    }

    private BackgroundTask pendingTask(BackgroundTaskType taskType, String businessKey) {
        return BackgroundTask.builder()
                .taskType(taskType)
                .businessKey(businessKey)
                .status(BackgroundTaskStatus.PENDING)
                .eventId(EVENT_ID)
                .correlationId(CORRELATION_ID)
                .requestedBy("ops.user")
                .messageJson("{\"eventId\":\"" + EVENT_ID + "\",\"type\":\"" + taskType.eventType().getWireValue()
                        + "\",\"version\":1,\"correlationId\":\"" + CORRELATION_ID + "\","
                        + "\"payload\":{\"email\":\"jane@example.com\"}}")
                .createdAt(CREATED_AT)
                .build();
    }

    private ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        relayLogger().addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        relayLogger().detachAppender(appender);
        appender.stop();
    }

    private Logger relayLogger() {
        return (Logger) LoggerFactory.getLogger(TaskOutboxRelay.class);
    }

    @Test
    void relayPending_confirmedPublish_marksTheTaskSubmittedAndPublishesItOnce() {
        BackgroundTask task = backgroundTaskRepository.saveAndFlush(
                pendingTask(BackgroundTaskType.DOCUMENT_SCAN, "7"));
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            relayAt(NOW).relayPending();
        } finally {
            detachAppender(appender);
        }

        assertThat(publisher.published).hasSize(1);
        assertThat(publisher.published.get(0).routingKey()).isEqualTo("task.document-scan");
        assertThat(publisher.published.get(0).messageJson()).isEqualTo(task.getMessageJson());

        BackgroundTask published = backgroundTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(published.getStatus()).isEqualTo(BackgroundTaskStatus.SUBMITTED);
        assertThat(published.getPublishedAt()).isEqualTo(NOW);
        assertThat(published.getNextAttemptAt()).isNull();
        assertThat(published.getAttempts()).isZero();

        // The log names the task without leaking the payload's personal data.
        assertThat(appender.list).anySatisfy(logged -> {
            assertThat(logged.getLevel().toString()).isEqualTo("INFO");
            assertThat(logged.getFormattedMessage())
                    .contains(String.valueOf(task.getId()))
                    .contains("DOCUMENT_SCAN")
                    .contains(EVENT_ID)
                    .contains(CORRELATION_ID)
                    .contains("banking.tasks.document-scan")
                    .doesNotContain("jane@example.com");
        });

        // A confirmed task is never picked up again, however often the relay runs.
        relayAt(NOW.plusSeconds(60)).relayPending();
        assertThat(publisher.published).hasSize(1);
        assertThat(backgroundTaskRepository.count()).isEqualTo(1L);
    }

    @Test
    void relayPending_publishFailure_keepsTheTaskPendingAndRetriesItLater() {
        BackgroundTask task = backgroundTaskRepository.saveAndFlush(
                pendingTask(BackgroundTaskType.STATEMENT_GENERATION, "10:2025-02-01:2025-02-28"));
        publisher.failure = new TaskPublishException("broker is down", "task.statement-generation", EVENT_ID);

        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            relayAt(NOW).relayPending();
        } finally {
            detachAppender(appender);
        }

        BackgroundTask failed = backgroundTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(BackgroundTaskStatus.PENDING);
        assertThat(failed.getPublishedAt()).isNull();
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(publisher.published).isEmpty();

        assertThat(appender.list).anySatisfy(logged -> {
            assertThat(logged.getLevel().toString()).isEqualTo("ERROR");
            assertThat(logged.getFormattedMessage())
                    .contains(String.valueOf(task.getId()))
                    .contains("STATEMENT_GENERATION")
                    .contains(EVENT_ID)
                    .contains(CORRELATION_ID)
                    .contains("banking.tasks.statement-generation")
                    .doesNotContain("jane@example.com");
        });

        // Not due yet at the same instant, so nothing is published by a second run.
        relayAt(NOW.plusSeconds(1)).relayPending();
        assertThat(publisher.published).isEmpty();

        // Once the broker is back, the same row is published - no new row, one message.
        publisher.failure = null;
        relayAt(NOW.plusSeconds(5)).relayPending();

        assertThat(publisher.published).hasSize(1);
        assertThat(publisher.published.get(0).routingKey()).isEqualTo("task.statement-generation");
        BackgroundTask retried = backgroundTaskRepository.findById(task.getId()).orElseThrow();
        assertThat(retried.getStatus()).isEqualTo(BackgroundTaskStatus.SUBMITTED);
        assertThat(retried.getPublishedAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(retried.getAttempts()).isEqualTo(1);
        assertThat(backgroundTaskRepository.count()).isEqualTo(1L);
    }

    @Test
    void relayPending_doesNotPublishARowThatIsNotCommitted() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> submitterFailure = new AtomicReference<>();

        Thread submitter = new Thread(() -> {
            TransactionTemplate businessTransaction = new TransactionTemplate(transactionManager);
            businessTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                businessTransaction.executeWithoutResult(status -> {
                    backgroundTaskRepository.saveAndFlush(pendingTask(BackgroundTaskType.DOCUMENT_SCAN, "11"));
                    inserted.countDown();
                    awaitQuietly(release);
                    status.setRollbackOnly();
                });
            } catch (Throwable ex) {
                submitterFailure.set(ex);
                inserted.countDown();
            }
        }, "uncommitted-task-submission");
        submitter.start();

        try {
            assertThat(inserted.await(10, TimeUnit.SECONDS))
                    .as("the uncommitted outbox row must have been written").isTrue();

            relayAt(NOW).relayPending();

            assertThat(publisher.published)
                    .as("a row whose business transaction has not committed must not be published")
                    .isEmpty();
        } finally {
            release.countDown();
            submitter.join(10_000);
        }
        assertThat(submitterFailure.get()).isNull();
    }

    @Test
    void theRelayIsOnlyScannedWhenTheOutboxRelayIsEnabled() {
        assertThat(relayIsScanned()).as("the relay runs by default").isTrue();
        assertThat(relayIsScanned("banking.tasks.relay-enabled", "true")).isTrue();
        assertThat(relayIsScanned("banking.tasks.relay-enabled", "false"))
                .as("banking.tasks.relay-enabled=false must keep the relay out of the context")
                .isFalse();
    }

    /** Asks Spring's component scanner whether the relay would be registered with these settings. */
    private static boolean relayIsScanned(String... properties) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < properties.length; i += 2) {
            environment.setProperty(properties[i], properties[i + 1]);
        }
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.setEnvironment(environment);
        scanner.addIncludeFilter(new AssignableTypeFilter(TaskOutboxRelay.class));
        return scanner.findCandidateComponents(TaskOutboxRelay.class.getPackageName()).stream()
                .anyMatch(candidate -> TaskOutboxRelay.class.getName().equals(candidate.getBeanClassName()));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** A stand-in for RabbitMQ that records what was published and can be told to fail. */
    private static final class RecordingPublisher implements TaskQueuePublisher {

        private record Sent(String routingKey, String messageJson) {}

        private final List<Sent> published = new ArrayList<>();

        private TaskPublishException failure;

        @Override
        public void publish(String routingKey, String messageJson) {
            if (failure != null) {
                throw failure;
            }
            published.add(new Sent(routingKey, messageJson));
        }
    }
}
