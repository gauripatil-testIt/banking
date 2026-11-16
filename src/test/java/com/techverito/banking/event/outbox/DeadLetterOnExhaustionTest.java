package com.techverito.banking.event.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.techverito.banking.entity.BusinessEventOutboxEntry;
import com.techverito.banking.entity.DeadLetterEntry;
import com.techverito.banking.entity.DeadLetterStatus;
import com.techverito.banking.entity.OutboxEventStatus;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.DeadLetterPublisher;
import com.techverito.banking.event.EventPublishException;
import com.techverito.banking.event.EventType;
import com.techverito.banking.repository.BusinessEventOutboxRepository;
import com.techverito.banking.repository.DeadLetterItemRepository;
import com.techverito.banking.service.DeadLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * What happens to an event that keeps failing: the dead-letter store and the timestamps
 * the record is built from. The tests run against the real database and a test double of
 * {@link BusinessEventBus}, so every attempt can be made to fail; no broker and no Docker
 * are involved.
 *
 * <p>The clock is movable, so the exact failure times the dead-letter record carries –
 * and the fact that the first one never moves while the last one follows every failure –
 * are assertions instead of approximations.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeadLetterOnExhaustionTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final Duration RETRY_INITIAL_DELAY = Duration.ofSeconds(1);

    private static final double RETRY_MULTIPLIER = 2;

    private static final int MAX_ATTEMPTS = 5;

    private static final String ACCOUNT_NINE_KEY = "account:9";

    private static final String CUSTOMER_ONE_KEY = "customer:1";

    private static final String CUSTOMER_SEVEN_KEY = "customer:7";

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

    /** A drain with the documented attempt limit and retry schedule. */
    private BusinessEventOutboxPublisher publisherWith(int maxAttempts) {
        return new BusinessEventOutboxPublisher(outboxRepository, eventBus, deadLetterPublisher, objectMapper,
                new OutboxProperties(maxAttempts, RETRY_INITIAL_DELAY, RETRY_MULTIPLIER, 200,
                        OutboxProperties.DEFAULT_DRAIN_INTERVAL), clock, deadLetterService);
    }

    /** The stored rows of one business key, oldest first. */
    private List<BusinessEventOutboxEntry> rowsOf(String businessKey) {
        return outboxRepository.findByBusinessKeyOrderByIdAsc(businessKey);
    }

    @Test
    void exhaustion_storesOneItemCarryingThePayloadIdReasonAttemptsAndFailureTimes() {
        BusinessEvent recorded = recorder.record(EventType.DOCUMENT_UPLOADED, 7L, null);
        eventBus.failingEventIds.add(recorded.eventId());
        BusinessEventOutboxPublisher fiveAttempts = publisherWith(MAX_ATTEMPTS);

        Instant firstFailure = clock.instant();
        fiveAttempts.drain();
        clock.advance(RETRY_INITIAL_DELAY);
        fiveAttempts.drain();
        clock.advance(Duration.ofSeconds(2));
        fiveAttempts.drain();
        clock.advance(Duration.ofSeconds(4));
        fiveAttempts.drain();
        clock.advance(Duration.ofSeconds(8));
        Instant lastFailure = clock.instant();
        fiveAttempts.drain();

        BusinessEventOutboxEntry outboxRow = rowsOf(CUSTOMER_SEVEN_KEY).get(0);
        assertThat(outboxRow.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(outboxRow.getAttempts()).isEqualTo(MAX_ATTEMPTS);

        List<DeadLetterEntry> items = deadLetterItemRepository.findAllByOrderByIdAsc();
        assertThat(items).hasSize(1);
        DeadLetterEntry item = items.get(0);
        assertThat(item.getSource()).isEqualTo("business-event-outbox");
        assertThat(item.getSource()).isEqualTo(DeadLetterService.SOURCE_BUSINESS_EVENT);
        assertThat(item.getSourceId()).isEqualTo(recorded.eventId());
        assertThat(item.getEventType()).isEqualTo(EventType.DOCUMENT_UPLOADED);
        assertThat(item.getPayload()).isEqualTo(outboxRow.getPayload());
        assertThat(item.getReason()).isEqualTo(outboxRow.getLastError());
        assertThat(item.getAttempts()).isEqualTo(MAX_ATTEMPTS);
        assertThat(item.getFirstFailedAt()).isEqualTo(firstFailure);
        assertThat(item.getLastFailedAt()).isEqualTo(lastFailure);
        assertThat(item.getDeadLetteredAt()).isEqualTo(lastFailure);
        assertThat(item.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(item.getReplayCount()).isZero();
        assertThat(item.getReplayedBy()).isNull();
        assertThat(item.getReplayedAt()).isNull();
        assertThat(eventBus.published).isEmpty();
    }

    @Test
    void everyFailureMovesTheLastFailureTimeWhileTheFirstOneNeverChanges() {
        BusinessEvent recorded = recorder.record(EventType.ACCOUNT_OPENED, 5L, 9L);
        eventBus.failingEventIds.add(recorded.eventId());
        BusinessEventOutboxPublisher fiveAttempts = publisherWith(MAX_ATTEMPTS);

        fiveAttempts.drain();

        BusinessEventOutboxEntry firstFailedRow = rowsOf(ACCOUNT_NINE_KEY).get(0);
        assertThat(firstFailedRow.getAttempts()).isEqualTo(1);
        assertThat(firstFailedRow.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(firstFailedRow.getLastFailedAt()).isEqualTo(NOW);

        clock.advance(RETRY_INITIAL_DELAY);
        fiveAttempts.drain();

        BusinessEventOutboxEntry secondFailedRow = rowsOf(ACCOUNT_NINE_KEY).get(0);
        assertThat(secondFailedRow.getAttempts()).isEqualTo(2);
        assertThat(secondFailedRow.getFirstFailedAt()).isEqualTo(NOW);
        assertThat(secondFailedRow.getLastFailedAt()).isEqualTo(NOW.plus(RETRY_INITIAL_DELAY));

        // Two failures are not the attempt limit, so nothing has been set aside yet.
        assertThat(deadLetterItemRepository.findAllByOrderByIdAsc()).isEmpty();
    }

    @Test
    void drainingAgainAfterExhaustion_keepsOneItemAndNeverSelectsTheFailedRowAgain() {
        BusinessEvent recorded = recorder.record(EventType.CUSTOMER_REGISTERED, 1L, null);
        eventBus.failingEventIds.add(recorded.eventId());
        BusinessEventOutboxPublisher twoAttempts = publisherWith(2);

        twoAttempts.drain();
        clock.advance(RETRY_INITIAL_DELAY);
        twoAttempts.drain();

        BusinessEventOutboxEntry failedRow = rowsOf(CUSTOMER_ONE_KEY).get(0);
        assertThat(failedRow.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(deadLetterItemRepository.findAllByOrderByIdAsc()).hasSize(1);

        clock.advance(Duration.ofMinutes(10));
        twoAttempts.drain();
        twoAttempts.drain();

        assertThat(deadLetterItemRepository.findAllByOrderByIdAsc()).hasSize(1);
        assertThat(rowsOf(CUSTOMER_ONE_KEY).get(0).getAttempts()).isEqualTo(2);
        assertThat(eventBus.published).isEmpty();

        // The event is still announced through the existing notice path, with its id and reason.
        assertThat(deadLetterPublisher.eventIds).containsExactly(recorded.eventId());
        assertThat(deadLetterPublisher.types).containsExactly(EventType.CUSTOMER_REGISTERED);
        assertThat(deadLetterPublisher.reasons).hasSize(1).allSatisfy(reason -> assertThat(reason)
                .contains("the broker did not acknowledge the record"));
    }

    @Test
    void recordingTheSameExhaustedRowTwice_storesOneItem() {
        BusinessEvent recorded = recorder.record(EventType.ACCOUNT_OPENED, 5L, 9L);
        eventBus.failingEventIds.add(recorded.eventId());
        BusinessEventOutboxPublisher twoAttempts = publisherWith(2);

        twoAttempts.drain();
        clock.advance(RETRY_INITIAL_DELAY);
        twoAttempts.drain();

        BusinessEventOutboxEntry failedRow = rowsOf(ACCOUNT_NINE_KEY).get(0);
        DeadLetterEntry recordedAgain = deadLetterService.recordBusinessEventFailure(failedRow);

        List<DeadLetterEntry> items = deadLetterItemRepository.findAllByOrderByIdAsc();
        assertThat(items).hasSize(1);
        assertThat(recordedAgain.getId()).isEqualTo(items.get(0).getId());
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

    /** Records what the dead-letter notice announced, without needing a broker. */
    private static final class RecordingDeadLetterPublisher implements DeadLetterPublisher {

        private final List<String> eventIds = new ArrayList<>();

        private final List<EventType> types = new ArrayList<>();

        private final List<String> reasons = new ArrayList<>();

        @Override
        public void deadLetter(BusinessEvent event, String reason) {
            eventIds.add(event.eventId());
            types.add(event.type());
            reasons.add(reason);
        }

        void reset() {
            eventIds.clear();
            types.clear();
            reasons.clear();
        }
    }

    /** A clock a test can move forward, so the exact failure times are checkable. */
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
