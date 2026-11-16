package com.techverito.banking.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.techverito.banking.entity.BusinessEventOutboxEntry;
import com.techverito.banking.entity.OutboxEventStatus;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.DeadLetterPublisher;
import com.techverito.banking.event.EventType;
import com.techverito.banking.repository.BusinessEventOutboxRepository;
import com.techverito.banking.repository.DeadLetterItemRepository;
import com.techverito.banking.service.DeadLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hand-off between a business transaction and the drain when the process does not
 * survive in between: the event is committed together with its business change, so a
 * publisher built after the "crash" still finds the row and publishes it exactly once, a
 * row that was already published is not published again, and a business change that rolls
 * back leaves neither the change nor the event it would have announced.
 *
 * <p>The publisher keeps no in-memory state about what it has done – everything it needs is
 * on the row – so a freshly built publisher stands in for a restarted process. The test runs
 * against the real database with a test double of {@link BusinessEventBus}, so every
 * publication is an assertion; no broker and no Docker are involved.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxCrashHandOffTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final Duration RETRY_INITIAL_DELAY = Duration.ofSeconds(1);

    private static final double RETRY_MULTIPLIER = 2;

    private static final int MAX_ATTEMPTS = 5;

    private static final String CUSTOMER_ONE_KEY = "customer:1";

    @Autowired
    BusinessEventOutboxRepository outboxRepository;

    @Autowired
    DeadLetterItemRepository deadLetterItemRepository;

    @Autowired
    PlatformTransactionManager transactionManager;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final MutableClock clock = new MutableClock(NOW);

    private final RecordingEventBus eventBus = new RecordingEventBus();

    private final RecordingDeadLetterPublisher deadLetterPublisher = new RecordingDeadLetterPublisher();

    private BusinessEventOutboxRecorder recorder;

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
    }

    /** A drain as a process that starts after the crash builds it: no state of its own. */
    private BusinessEventOutboxPublisher restartedPublisher() {
        return new BusinessEventOutboxPublisher(outboxRepository, eventBus, deadLetterPublisher, objectMapper,
                new OutboxProperties(MAX_ATTEMPTS, RETRY_INITIAL_DELAY, RETRY_MULTIPLIER, 200,
                        OutboxProperties.DEFAULT_DRAIN_INTERVAL), clock, deadLetterService);
    }

    /** The stored rows of one business key, oldest first. */
    private List<BusinessEventOutboxEntry> rowsOf(String businessKey) {
        return outboxRepository.findByBusinessKeyOrderByIdAsc(businessKey);
    }

    @Test
    void committedRowIsPublishedExactlyOnceByAFreshPublisher() {
        // The business transaction commits the customer and its event together, and the
        // process dies before the drain ever runs: the row is all that is left of it.
        BusinessEvent recorded = recorder.record(EventType.CUSTOMER_REGISTERED, 1L, null);
        assertThat(eventBus.published).isEmpty();
        assertThat(rowsOf(CUSTOMER_ONE_KEY)).hasSize(1);

        BusinessEventOutboxPublisher afterRestart = restartedPublisher();
        afterRestart.drain();

        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(recorded.eventId());
        BusinessEventOutboxEntry publishedRow = rowsOf(CUSTOMER_ONE_KEY).get(0);
        assertThat(publishedRow.getEventId()).isEqualTo(recorded.eventId());
        assertThat(publishedRow.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(publishedRow.getPublishedAt()).isEqualTo(NOW);
        assertThat(publishedRow.getAttempts()).isZero();

        // Neither this publisher nor another freshly built one publishes it a second time.
        afterRestart.drain();
        clock.advance(Duration.ofMinutes(10));
        restartedPublisher().drain();

        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(recorded.eventId());
        assertThat(rowsOf(CUSTOMER_ONE_KEY).get(0).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(deadLetterItemRepository.findAllByOrderByIdAsc()).isEmpty();
    }

    @Test
    void alreadyPublishedRowIsNotPublishedAgainAfterRestart() {
        BusinessEvent recorded = recorder.record(EventType.CUSTOMER_REGISTERED, 1L, null);

        // The event went out before the crash: the row was marked PUBLISHED and committed.
        restartedPublisher().drain();
        assertThat(eventBus.published).hasSize(1);
        assertThat(rowsOf(CUSTOMER_ONE_KEY).get(0).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);

        // The process comes back and drains again; the committed row keeps it from repeating.
        clock.advance(Duration.ofHours(1));
        restartedPublisher().drain();
        restartedPublisher().drain();

        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(recorded.eventId());
        assertThat(rowsOf(CUSTOMER_ONE_KEY)).hasSize(1);
        assertThat(rowsOf(CUSTOMER_ONE_KEY).get(0).getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
        assertThat(deadLetterItemRepository.findAllByOrderByIdAsc()).isEmpty();
    }

    @Test
    void rolledBackChangeLeavesNoOutboxRowAndPublishesNothing() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // The customer action records its event and then fails, so the transaction rolls back.
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            recorder.record(EventType.CUSTOMER_REGISTERED, 1L, null);
            throw new IllegalStateException("the business change could not be committed");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be committed");

        // Nothing was announced for a change that did not happen, and no saved change was
        // left without its event: there is no row at all, so a later drain finds nothing.
        assertThat(outboxRepository.findAll()).isEmpty();
        assertThat(eventBus.published).isEmpty();

        restartedPublisher().drain();

        assertThat(eventBus.published).isEmpty();
        assertThat(deadLetterItemRepository.findAllByOrderByIdAsc()).isEmpty();
    }

    /** The bus a test drives: records what would have been published. */
    private static final class RecordingEventBus implements BusinessEventBus {

        private final List<BusinessEvent> published = new ArrayList<>();

        @Override
        public void publish(BusinessEvent event) {
            published.add(event);
        }

        @Override
        public void subscribe(EventType type, Consumer<BusinessEvent> handler) {
            throw new UnsupportedOperationException("the outbox drain only publishes");
        }

        void reset() {
            published.clear();
        }
    }

    /** Records what the dead-letter notice announced, without needing a broker. */
    private static final class RecordingDeadLetterPublisher implements DeadLetterPublisher {

        private final List<String> eventIds = new ArrayList<>();

        @Override
        public void deadLetter(BusinessEvent event, String reason) {
            eventIds.add(event.eventId());
        }

        void reset() {
            eventIds.clear();
        }
    }

    /** A clock a test can move forward, so a restart can be shown to drain later in time. */
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
