package com.techverito.banking.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.techverito.banking.dto.DeadLetterResponse;
import com.techverito.banking.entity.DeadLetterEntry;
import com.techverito.banking.entity.DeadLetterStatus;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.EventType;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.DeadLetterItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replaying a dead-lettered item against the real database and a test double of
 * {@link BusinessEventBus}, so what a replay publishes and what it records are both
 * assertions. No broker and no Docker are involved.
 *
 * <p>What it shows: the stored envelope is republished unchanged – so the replay carries the
 * original event id, which is what makes a duplicate-safe consumer skip the repeat – the
 * item records who replayed it, when and how many times, and a batch that names an unknown
 * item replays nothing at all.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DeadLetterReplayServiceTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final String SOURCE = "business-event-outbox";

    private static final long UNKNOWN_ID = 9999L;

    @Autowired
    DeadLetterItemRepository deadLetterItemRepository;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final RecordingEventBus eventBus = new RecordingEventBus();

    private DeadLetterReplayService replayService;

    @BeforeEach
    void setUp() {
        deadLetterItemRepository.deleteAll();
        eventBus.reset();
        replayService = new DeadLetterReplayService(deadLetterItemRepository, eventBus, objectMapper, clock);
    }

    /** A stored envelope, as the drain recorded it before it was set aside. */
    private BusinessEvent event(EventType type) {
        return BusinessEvent.create(type, 1L, null, "ops.user", "corr-dead-letter", clock);
    }

    /** The item as the database keeps it now, so an assertion reads what was committed. */
    private DeadLetterEntry stored(DeadLetterEntry item) {
        return deadLetterItemRepository.findById(item.getId()).orElseThrow();
    }

    private DeadLetterEntry store(BusinessEvent event) {
        return deadLetterItemRepository.save(DeadLetterEntry.builder()
                .source(SOURCE)
                .sourceId(event.eventId())
                .eventType(event.type())
                .payload(serialise(event))
                .reason("the broker did not acknowledge the record")
                .attempts(5)
                .firstFailedAt(NOW.minusSeconds(15))
                .lastFailedAt(NOW)
                .deadLetteredAt(NOW)
                .status(DeadLetterStatus.PENDING)
                .replayCount(0)
                .build());
    }

    private String serialise(BusinessEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void list_returnsEveryItemOldestFirstAndOnlyTheRequestedState() {
        DeadLetterEntry pending = store(event(EventType.CUSTOMER_REGISTERED));
        DeadLetterEntry replayed = store(event(EventType.ACCOUNT_OPENED));
        replayService.replay(replayed.getId(), "ops.alice");

        List<DeadLetterResponse> everyItem = replayService.list(null);
        assertThat(everyItem).extracting(DeadLetterResponse::id)
                .containsExactly(pending.getId(), replayed.getId());
        assertThat(everyItem).extracting(DeadLetterResponse::status).containsExactly(DeadLetterStatus.PENDING,
                DeadLetterStatus.REPLAYED);

        assertThat(replayService.list(DeadLetterStatus.PENDING)).extracting(DeadLetterResponse::id)
                .containsExactly(pending.getId());
        assertThat(replayService.list(DeadLetterStatus.REPLAYED)).extracting(DeadLetterResponse::id)
                .containsExactly(replayed.getId());
    }

    @Test
    void get_carriesEverythingTheItemKept() {
        BusinessEvent original = event(EventType.DOCUMENT_UPLOADED);
        DeadLetterEntry item = store(original);

        DeadLetterResponse response = replayService.get(item.getId());

        assertThat(response.source()).isEqualTo(SOURCE);
        assertThat(response.sourceId()).isEqualTo(original.eventId());
        assertThat(response.eventType()).isEqualTo(EventType.DOCUMENT_UPLOADED);
        assertThat(response.payload()).isEqualTo(serialise(original));
        assertThat(response.reason()).isEqualTo("the broker did not acknowledge the record");
        assertThat(response.attempts()).isEqualTo(5);
        assertThat(response.firstFailedAt()).isEqualTo(NOW.minusSeconds(15));
        assertThat(response.lastFailedAt()).isEqualTo(NOW);
        assertThat(response.deadLetteredAt()).isEqualTo(NOW);
        assertThat(response.status()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(response.replayCount()).isZero();
    }

    @Test
    void get_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> replayService.get(UNKNOWN_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(String.valueOf(UNKNOWN_ID));
    }

    @Test
    void replay_publishesTheStoredEnvelopeUnchangedAndRecordsWhoReplayedItAndWhen() {
        BusinessEvent original = event(EventType.CUSTOMER_REGISTERED);
        DeadLetterEntry item = store(original);

        DeadLetterResponse response = replayService.replay(item.getId(), "ops.alice");

        // The envelope is republished as it was recorded, so the replay carries the original id.
        assertThat(eventBus.published).containsExactly(original);
        assertThat(eventBus.published.get(0).eventId()).isEqualTo(item.getSourceId());

        assertThat(response.status()).isEqualTo(DeadLetterStatus.REPLAYED);
        assertThat(response.replayedBy()).isEqualTo("ops.alice");
        assertThat(response.replayedAt()).isEqualTo(NOW);
        assertThat(response.replayCount()).isEqualTo(1);

        DeadLetterEntry replayedRow = stored(item);
        assertThat(replayedRow.getStatus()).isEqualTo(DeadLetterStatus.REPLAYED);
        assertThat(replayedRow.getReplayedBy()).isEqualTo("ops.alice");
        assertThat(replayedRow.getReplayedAt()).isEqualTo(NOW);
        assertThat(replayedRow.getReplayCount()).isEqualTo(1);
        // It is not deleted: a replayed item keeps its payload and its history.
        assertThat(deadLetterItemRepository.findAllByOrderByIdAsc()).hasSize(1);
    }

    @Test
    void replay_againPublishesTheSameEventIdAndCountsTheSecondReplay() {
        BusinessEvent original = event(EventType.EXPORT_REQUESTED);
        DeadLetterEntry item = store(original);

        replayService.replay(item.getId(), "ops.alice");
        DeadLetterResponse second = replayService.replay(item.getId(), "ops.bob");

        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(original.eventId(), original.eventId());
        assertThat(second.replayCount()).isEqualTo(2);
        assertThat(second.replayedBy()).isEqualTo("ops.bob");
        assertThat(stored(item).getReplayCount()).isEqualTo(2);
    }

    @Test
    void replay_blankReplayedBy_publishesNothingAndLeavesTheItemPending() {
        DeadLetterEntry item = store(event(EventType.STATEMENT_GENERATED));

        assertThatThrownBy(() -> replayService.replay(item.getId(), " "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> replayService.replay(item.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(eventBus.published).isEmpty();
        DeadLetterEntry untouched = stored(item);
        assertThat(untouched.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(untouched.getReplayCount()).isZero();
        assertThat(untouched.getReplayedBy()).isNull();
        assertThat(untouched.getReplayedAt()).isNull();
    }

    @Test
    void replayBatch_publishesEveryItemAndRecordsWhoReplayedThemAndWhen() {
        BusinessEvent first = event(EventType.CUSTOMER_REGISTERED);
        BusinessEvent second = event(EventType.ACCOUNT_OPENED);
        DeadLetterEntry firstItem = store(first);
        DeadLetterEntry secondItem = store(second);

        List<DeadLetterResponse> replayed = replayService.replayBatch(
                List.of(firstItem.getId(), secondItem.getId()), "ops.alice");

        assertThat(replayed).extracting(DeadLetterResponse::id)
                .containsExactly(firstItem.getId(), secondItem.getId());
        assertThat(replayed).allSatisfy(response -> {
            assertThat(response.status()).isEqualTo(DeadLetterStatus.REPLAYED);
            assertThat(response.replayedBy()).isEqualTo("ops.alice");
            assertThat(response.replayedAt()).isEqualTo(NOW);
            assertThat(response.replayCount()).isEqualTo(1);
        });
        assertThat(eventBus.published).extracting(BusinessEvent::eventId)
                .containsExactly(first.eventId(), second.eventId());
    }

    @Test
    void replayBatch_withAnUnknownId_replaysNothingAtAll() {
        BusinessEvent original = event(EventType.CUSTOMER_REGISTERED);
        DeadLetterEntry item = store(original);

        assertThatThrownBy(() -> replayService.replayBatch(List.of(item.getId(), UNKNOWN_ID), "ops.alice"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(String.valueOf(UNKNOWN_ID));

        assertThat(eventBus.published).isEmpty();
        DeadLetterEntry untouched = stored(item);
        assertThat(untouched.getStatus()).isEqualTo(DeadLetterStatus.PENDING);
        assertThat(untouched.getReplayCount()).isZero();
        assertThat(untouched.getReplayedBy()).isNull();
    }

    /** The bus a test drives: records what a replay would have published. */
    private static final class RecordingEventBus implements BusinessEventBus {

        private final List<BusinessEvent> published = new ArrayList<>();

        @Override
        public void publish(BusinessEvent event) {
            published.add(event);
        }

        @Override
        public void subscribe(EventType type, Consumer<BusinessEvent> handler) {
            throw new UnsupportedOperationException("the replay service only publishes");
        }

        void reset() {
            published.clear();
        }
    }
}
