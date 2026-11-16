package com.techverito.banking.event.outbox;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.techverito.banking.entity.BusinessEventOutboxEntry;
import com.techverito.banking.entity.OutboxEventStatus;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.DeadLetterPublisher;
import com.techverito.banking.event.EventPublishException;
import com.techverito.banking.event.EventType;
import com.techverito.banking.event.LoggingDeadLetterPublisher;
import com.techverito.banking.repository.BusinessEventOutboxRepository;
import com.techverito.banking.repository.DeadLetterItemRepository;
import com.techverito.banking.service.DeadLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The scheduled outbox drain against the real database and a test double of
 * {@link BusinessEventBus}, so a test can decide which publishes fail. That is how
 * retries, per-key ordering, "one account blocked, another still published" and the
 * dead-letter outcome become assertions instead of hopes. No broker and no Docker are
 * involved; the real-broker proof is {@code OutboxEndToEndKafkaContainerTest}.
 *
 * <p>The test is non-transactional, so every {@code save} the drain does is committed
 * the way the application commits it, and the clock is movable so a row that is not yet
 * due can be shown to wait.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BusinessEventOutboxPublisherTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final Duration RETRY_INITIAL_DELAY = Duration.ofSeconds(1);

    private static final double RETRY_MULTIPLIER = 2;

    private static final int MAX_ATTEMPTS = 3;

    private static final String ACCOUNT_NINE_KEY = "account:9";

    private static final String ACCOUNT_TEN_KEY = "account:10";

    private static final String CUSTOMER_ONE_KEY = "customer:1";

    @Autowired
    BusinessEventOutboxRepository outboxRepository;

    @Autowired
    DeadLetterItemRepository deadLetterItemRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final MutableClock clock = new MutableClock(NOW);

    private final RecordingEventBus eventBus = new RecordingEventBus();

    private final RecordingDeadLetterPublisher deadLetterPublisher = new RecordingDeadLetterPublisher();

    private BusinessEventOutboxRecorder recorder;

    private BusinessEventOutboxPublisher publisher;

    private DeadLetterService deadLetterService;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        deadLetterItemRepository.deleteAll();
        clock.reset(NOW);
        eventBus.reset();
        deadLetterPublisher.reset();
        recorder = new BusinessEventOutboxRecorder(outboxRepository, objectMapper, clock, new EventActorContext());
        deadLetterService = new DeadLetterService(deadLetterItemRepository);
        publisher = new BusinessEventOutboxPublisher(outboxRepository, eventBus, deadLetterPublisher,
                objectMapper, new OutboxProperties(MAX_ATTEMPTS, RETRY_INITIAL_DELAY, RETRY_MULTIPLIER, 200,
                        OutboxProperties.DEFAULT_DRAIN_INTERVAL), clock, deadLetterService);
    }

    /** A drain configured with its own attempt limit and retry schedule, for the schedule assertions. */
    private BusinessEventOutboxPublisher publisherWith(int maxAttempts, Duration retryInitialDelay,
                                                       double retryMultiplier) {
        return new BusinessEventOutboxPublisher(outboxRepository, eventBus, deadLetterPublisher, objectMapper,
                new OutboxProperties(maxAttempts, retryInitialDelay, retryMultiplier, 200,
                        OutboxProperties.DEFAULT_DRAIN_INTERVAL), clock, deadLetterService);
    }

    /** The stored rows of one business key, oldest first. */
    private List<BusinessEventOutboxEntry> rowsOf(String businessKey) {
        return outboxRepository.findByBusinessKeyOrderByIdAsc(businessKey);
    }

    /** A row as the recorder writes it, for the cases a test has to set up directly. */
    private BusinessEventOutboxEntry storedRow(BusinessEvent event, OutboxEventStatus status,
                                               Instant nextAttemptAt) {
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
                    .status(status)
                    .attempts(0)
                    .nextAttemptAt(nextAttemptAt)
                    .createdAt(clock.instant())
                    .build();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void drain_publishesTheEventsOfOneAccountInTheOrderTheyHappened() {
        BusinessEvent opened = recorder.record(EventType.ACCOUNT_OPENED, 5L, 9L);
        BusinessEvent statusChanged = recorder.record(EventType.ACCOUNT_STATUS_CHANGED, 5L, 9L);

        publisher.drain();

        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(opened.eventId(), statusChanged.eventId());
        assertThat(eventBus.published).extracting(BusinessEvent::type)
                .containsExactly(EventType.ACCOUNT_OPENED, EventType.ACCOUNT_STATUS_CHANGED);

        List<BusinessEventOutboxEntry> rows = rowsOf(ACCOUNT_NINE_KEY);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
            assertThat(row.getPublishedAt()).isEqualTo(NOW);
            assertThat(row.getAttempts()).isZero();
        });
    }

    @Test
    void drain_doesNotPublishARowWhileAnEarlierRowOfTheSameAccountIsStillUnresolved() {
        BusinessEvent earlier = BusinessEvent.create(EventType.ACCOUNT_OPENED, 5L, 9L, "ops.user",
                "corr-earlier", clock);
        outboxRepository.save(storedRow(earlier, OutboxEventStatus.PENDING, NOW.plusSeconds(600)));
        BusinessEvent blocked = recorder.record(EventType.ACCOUNT_STATUS_CHANGED, 5L, 9L);
        BusinessEvent otherAccount = recorder.record(EventType.ACCOUNT_OPENED, 5L, 10L);

        publisher.drain();

        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(otherAccount.eventId());

        BusinessEventOutboxEntry blockedRow = rowsOf(ACCOUNT_NINE_KEY).get(1);
        assertThat(blockedRow.getEventId()).isEqualTo(blocked.eventId());
        assertThat(blockedRow.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(blockedRow.getAttempts()).isZero();
        assertThat(blockedRow.getNextAttemptAt()).isEqualTo(NOW);

        assertThat(rowsOf(ACCOUNT_TEN_KEY).get(0).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    void drain_keepsAFailedAccountPendingWhileAnotherAccountsEventIsStillPublished() {
        BusinessEvent failing = recorder.record(EventType.ACCOUNT_OPENED, 5L, 9L);
        BusinessEvent otherAccount = recorder.record(EventType.ACCOUNT_OPENED, 5L, 10L);
        eventBus.failingEventIds.add(failing.eventId());

        publisher.drain();

        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(otherAccount.eventId());

        BusinessEventOutboxEntry failedRow = rowsOf(ACCOUNT_NINE_KEY).get(0);
        assertThat(failedRow.getEventId()).isEqualTo(failing.eventId());
        assertThat(failedRow.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(failedRow.getAttempts()).isEqualTo(1);
        assertThat(rowsOf(ACCOUNT_TEN_KEY).get(0).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    void drain_retriesAFailedRowOnceItIsDueAndThenPublishesItExactlyOnce() {
        BusinessEvent recorded = recorder.record(EventType.ACCOUNT_OPENED, 5L, 9L);
        eventBus.failingEventIds.add(recorded.eventId());

        publisher.drain();

        BusinessEventOutboxEntry failedRow = rowsOf(ACCOUNT_NINE_KEY).get(0);
        assertThat(failedRow.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(failedRow.getAttempts()).isEqualTo(1);
        assertThat(failedRow.getLastError())
                .contains(recorded.eventId())
                .contains("the broker did not acknowledge the record");
        assertThat(failedRow.getNextAttemptAt()).isEqualTo(NOW.plus(RETRY_INITIAL_DELAY));
        assertThat(eventBus.published).isEmpty();

        // Not due yet, so the next drain leaves it alone.
        publisher.drain();
        assertThat(rowsOf(ACCOUNT_NINE_KEY).get(0).getAttempts()).isEqualTo(1);
        assertThat(eventBus.published).isEmpty();

        clock.advance(RETRY_INITIAL_DELAY);
        eventBus.failingEventIds.clear();
        publisher.drain();

        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(recorded.eventId());
        BusinessEventOutboxEntry publishedRow = rowsOf(ACCOUNT_NINE_KEY).get(0);
        assertThat(publishedRow.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(publishedRow.getPublishedAt()).isEqualTo(clock.instant());
        assertThat(publishedRow.getLastError()).isNull();
    }

    @Test
    void drain_neverPublishesAnAlreadyPublishedEventAgain() {
        BusinessEvent recorded = recorder.record(EventType.CUSTOMER_REGISTERED, 1L, null);

        publisher.drain();
        publisher.drain();
        clock.advance(RETRY_INITIAL_DELAY);
        publisher.drain();

        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(recorded.eventId());
        assertThat(rowsOf(CUSTOMER_ONE_KEY)).hasSize(1);
        assertThat(rowsOf(CUSTOMER_ONE_KEY).get(0).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    void drain_keepsTheRowFailedAndDeadLettersTheEventWithItsReasonOnceAttemptsAreExhausted() {
        BusinessEvent recorded = recorder.record(EventType.DOCUMENT_UPLOADED, 7L, null);
        eventBus.failingEventIds.add(recorded.eventId());

        Logger logger = (Logger) LoggerFactory.getLogger(LoggingDeadLetterPublisher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                publisher.drain();
                if (attempt < MAX_ATTEMPTS) {
                    clock.advance(RETRY_INITIAL_DELAY.multipliedBy((long) Math.pow(RETRY_MULTIPLIER, attempt - 1)));
                }
            }
            // A FAILED row is not PENDING, so it is never selected again.
            publisher.drain();

            List<BusinessEventOutboxEntry> rows = outboxRepository.findAll();
            assertThat(rows).hasSize(1);
            BusinessEventOutboxEntry failedRow = rows.get(0);
            assertThat(failedRow.getEventId()).isEqualTo(recorded.eventId());
            assertThat(failedRow.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
            assertThat(failedRow.getAttempts()).isEqualTo(MAX_ATTEMPTS);
            assertThat(failedRow.getLastError()).contains("the broker did not acknowledge the record");
            assertThat(failedRow.getDeadLetteredAt()).isEqualTo(clock.instant());
            assertThat(failedRow.getPublishedAt()).isNull();

            assertThat(deadLetterPublisher.eventIds).containsExactly(recorded.eventId());
            assertThat(deadLetterPublisher.types).containsExactly(EventType.DOCUMENT_UPLOADED);
            assertThat(deadLetterPublisher.reasons).hasSize(1)
                    .allSatisfy(reason -> assertThat(reason)
                            .contains("the broker did not acknowledge the record"));

            assertThat(appender.list).hasSize(1);
            ILoggingEvent reported = appender.list.get(0);
            assertThat(reported.getLevel()).isEqualTo(Level.ERROR);
            assertThat(reported.getFormattedMessage())
                    .contains(recorded.eventId())
                    .contains("DOCUMENT_UPLOADED")
                    .contains("the broker did not acknowledge the record");

            assertThat(eventBus.published).isEmpty();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void drain_givesExactlyFiveAttemptsOnTheExponentialScheduleThenStops() {
        BusinessEvent recorded = recorder.record(EventType.ACCOUNT_OPENED, 5L, 9L);
        eventBus.failingEventIds.add(recorded.eventId());
        BusinessEventOutboxPublisher fiveAttempts = publisherWith(5, RETRY_INITIAL_DELAY, RETRY_MULTIPLIER);

        fiveAttempts.drain();
        assertThat(rowsOf(ACCOUNT_NINE_KEY).get(0).getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));

        clock.advance(Duration.ofSeconds(1));
        Instant secondFailure = clock.instant();
        fiveAttempts.drain();
        BusinessEventOutboxEntry secondAttempt = rowsOf(ACCOUNT_NINE_KEY).get(0);
        assertThat(secondAttempt.getAttempts()).isEqualTo(2);
        assertThat(secondAttempt.getNextAttemptAt()).isEqualTo(secondFailure.plusSeconds(2));

        clock.advance(Duration.ofSeconds(2));
        Instant thirdFailure = clock.instant();
        fiveAttempts.drain();
        BusinessEventOutboxEntry thirdAttempt = rowsOf(ACCOUNT_NINE_KEY).get(0);
        assertThat(thirdAttempt.getAttempts()).isEqualTo(3);
        assertThat(thirdAttempt.getNextAttemptAt()).isEqualTo(thirdFailure.plusSeconds(4));

        clock.advance(Duration.ofSeconds(4));
        Instant fourthFailure = clock.instant();
        fiveAttempts.drain();
        BusinessEventOutboxEntry fourthAttempt = rowsOf(ACCOUNT_NINE_KEY).get(0);
        assertThat(fourthAttempt.getAttempts()).isEqualTo(4);
        assertThat(fourthAttempt.getNextAttemptAt()).isEqualTo(fourthFailure.plusSeconds(8));

        clock.advance(Duration.ofSeconds(8));
        fiveAttempts.drain();

        BusinessEventOutboxEntry exhausted = rowsOf(ACCOUNT_NINE_KEY).get(0);
        assertThat(exhausted.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(exhausted.getAttempts()).isEqualTo(5);
        assertThat(exhausted.getPublishedAt()).isNull();
        assertThat(eventBus.published).isEmpty();

        // After the fifth failure no further attempt is scheduled for it.
        clock.advance(Duration.ofMinutes(10));
        fiveAttempts.drain();
        assertThat(rowsOf(ACCOUNT_NINE_KEY).get(0).getAttempts()).isEqualTo(5);
        assertThat(eventBus.published).isEmpty();
    }

    @Test
    void drain_allowsExactlyOneRetryWhenTheAttemptLimitIsTwo() {
        BusinessEvent recorded = recorder.record(EventType.CUSTOMER_REGISTERED, 1L, null);
        eventBus.failingEventIds.add(recorded.eventId());
        BusinessEventOutboxPublisher twoAttempts = publisherWith(2, Duration.ofSeconds(5), RETRY_MULTIPLIER);

        twoAttempts.drain();
        BusinessEventOutboxEntry firstFailure = rowsOf(CUSTOMER_ONE_KEY).get(0);
        assertThat(firstFailure.getAttempts()).isEqualTo(1);
        assertThat(firstFailure.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(5));

        // The only retry runs five seconds later, and its failure gives up on the event.
        clock.advance(Duration.ofSeconds(5));
        twoAttempts.drain();

        BusinessEventOutboxEntry exhausted = rowsOf(CUSTOMER_ONE_KEY).get(0);
        assertThat(exhausted.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(exhausted.getAttempts()).isEqualTo(2);
        assertThat(eventBus.published).isEmpty();
    }

    @Test
    void drain_parksAPoisonEventWithoutHoldingUpLaterRowsOrOtherBusinessKeys() {
        BusinessEvent poison = recorder.record(EventType.ACCOUNT_OPENED, 5L, 9L);
        BusinessEvent otherAccount = recorder.record(EventType.ACCOUNT_OPENED, 5L, 10L);
        eventBus.failingEventIds.add(poison.eventId());
        BusinessEventOutboxPublisher twoAttempts = publisherWith(2, RETRY_INITIAL_DELAY, RETRY_MULTIPLIER);

        twoAttempts.drain();

        // The failing account waits for its retry while the other account still goes out.
        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(otherAccount.eventId());
        assertThat(rowsOf(ACCOUNT_NINE_KEY).get(0).getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        clock.advance(RETRY_INITIAL_DELAY);
        twoAttempts.drain();

        BusinessEventOutboxEntry poisonRow = rowsOf(ACCOUNT_NINE_KEY).get(0);
        assertThat(poisonRow.getEventId()).isEqualTo(poison.eventId());
        assertThat(poisonRow.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(poisonRow.getAttempts()).isEqualTo(2);

        // A FAILED row is never selected again, so a later event of the same key is not blocked.
        BusinessEvent laterSameAccount = recorder.record(EventType.ACCOUNT_STATUS_CHANGED, 5L, 9L);
        twoAttempts.drain();

        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(otherAccount.eventId(), laterSameAccount.eventId());
        assertThat(rowsOf(ACCOUNT_NINE_KEY)).extracting(BusinessEventOutboxEntry::getStatus)
                .containsExactly(OutboxEventStatus.FAILED, OutboxEventStatus.PUBLISHED);
    }

    /** The bus a test drives: records what would have been published, fails what it is told to. */
    private static final class RecordingEventBus implements BusinessEventBus {

        private final List<BusinessEvent> published = new ArrayList<>();

        private final Set<String> failingEventIds = new HashSet<>();

        @Override
        public void publish(BusinessEvent event) {
            if (failingEventIds.contains(event.eventId())) {
                throw new EventPublishException(event.eventId(), "the broker did not acknowledge the record",
                        new IllegalStateException("the broker is unreachable"));
            }
            published.add(event);
        }

        @Override
        public void subscribe(EventType type, Consumer<BusinessEvent> handler) {
            throw new UnsupportedOperationException("the outbox drain only publishes");
        }

        void reset() {
            published.clear();
            failingEventIds.clear();
        }
    }

    /** Records what was dead-lettered and reports it through the real logging publisher. */
    private static final class RecordingDeadLetterPublisher implements DeadLetterPublisher {

        private final DeadLetterPublisher delegate = new LoggingDeadLetterPublisher();

        private final List<String> eventIds = new ArrayList<>();

        private final List<EventType> types = new ArrayList<>();

        private final List<String> reasons = new ArrayList<>();

        @Override
        public void deadLetter(BusinessEvent event, String reason) {
            eventIds.add(event.eventId());
            types.add(event.type());
            reasons.add(reason);
            delegate.deadLetter(event, reason);
        }

        void reset() {
            eventIds.clear();
            types.clear();
            reasons.clear();
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
