package com.techverito.banking.event.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.techverito.banking.entity.DeadLetterEntry;
import com.techverito.banking.entity.DeadLetterStatus;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.EventType;
import com.techverito.banking.event.InMemoryEventBus;
import com.techverito.banking.repository.DeadLetterItemRepository;
import com.techverito.banking.repository.ProcessedEventRepository;
import com.techverito.banking.service.DeadLetterReplayService;
import com.techverito.banking.service.ProcessedEventRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The subscriber seam: a handler registered through it receives an event once, even when the
 * same event id is delivered again – which is what a replay of a dead-lettered item does – and a
 * handler that throws leaves no processed marker, so the event is handled again on its next
 * delivery instead of being skipped as done.
 *
 * <p>The bus is the real in-memory one, so delivery is synchronous and the assertions read what
 * a handler actually received. No broker and no Docker are involved.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({IdempotentBusinessEventSubscriber.class, ProcessedEventRegistry.class,
        IdempotentBusinessEventSubscriberTest.SubscriberTestConfig.class})
class IdempotentBusinessEventSubscriberTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @Autowired
    DeadLetterItemRepository deadLetterItemRepository;

    @Autowired
    IdempotentBusinessEventSubscriber subscriber;

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private final InMemoryEventBus bus = new InMemoryEventBus();

    @BeforeEach
    void clearRegistry() {
        processedEventRepository.deleteAll();
        deadLetterItemRepository.deleteAll();
    }

    private BusinessEvent event(EventType type) {
        return BusinessEvent.create(type, 1L, null, "ops.user", "corr-subscriber", CLOCK);
    }

    @Test
    void aHandlerRegisteredThroughTheSubscriber_receivesAnEventOnceEvenWhenItsIdIsDeliveredAgain() {
        List<BusinessEvent> handled = new ArrayList<>();
        subscriber.subscribe(bus, EventType.CUSTOMER_REGISTERED, handled::add);

        BusinessEvent event = event(EventType.CUSTOMER_REGISTERED);
        bus.publish(event);
        // A replay republishes the stored envelope, so the second delivery carries the same id.
        bus.publish(event);

        assertThat(handled).containsExactly(event);
        assertThat(handled.get(0).eventId()).isEqualTo(event.eventId());
        assertThat(processedEventRepository.existsByEventId(event.eventId())).isTrue();
        assertThat(processedEventRepository.findAll()).hasSize(1);
    }

    @Test
    void aHandlerThatThrows_leavesNoMarkerSoTheNextDeliveryRunsItAgain() {
        List<BusinessEvent> attempts = new ArrayList<>();
        subscriber.subscribe(bus, EventType.ACCOUNT_OPENED, event -> {
            attempts.add(event);
            throw new IllegalStateException("the handler could not apply its effect");
        });

        BusinessEvent event = event(EventType.ACCOUNT_OPENED);

        assertThatThrownBy(() -> bus.publish(event)).isInstanceOf(IllegalStateException.class);
        assertThat(processedEventRepository.existsByEventId(event.eventId())).isFalse();

        assertThatThrownBy(() -> bus.publish(event)).isInstanceOf(IllegalStateException.class);
        assertThat(attempts).containsExactly(event, event);
    }

    @Test
    void aHandlerRegisteredForOneType_isNotInvokedForAnotherType() {
        List<BusinessEvent> handled = new ArrayList<>();
        subscriber.subscribe(bus, EventType.EXPORT_REQUESTED, handled::add);

        bus.publish(event(EventType.DOCUMENT_UPLOADED));

        assertThat(handled).isEmpty();
        assertThat(processedEventRepository.findAll()).isEmpty();
    }

    @Test
    void replayingADeadLetteredItemWhoseEventWasAlreadyHandled_publishesTheSameIdAndRunsTheEffectOnce() {
        List<BusinessEvent> handled = new ArrayList<>();
        subscriber.subscribe(bus, EventType.CUSTOMER_REGISTERED, handled::add);

        // The event reached the handler when it was first published, so its effect has run.
        BusinessEvent original = event(EventType.CUSTOMER_REGISTERED);
        bus.publish(original);
        assertThat(handled).hasSize(1);

        // Operations now replay that event out of the dead-letter store.
        DeadLetterEntry item = deadLetterItemRepository.save(DeadLetterEntry.builder()
                .source("business-event-outbox")
                .sourceId(original.eventId())
                .eventType(original.type())
                .payload(serialise(original))
                .reason("the broker did not acknowledge the record")
                .attempts(5)
                .firstFailedAt(NOW)
                .lastFailedAt(NOW)
                .deadLetteredAt(NOW)
                .status(DeadLetterStatus.PENDING)
                .replayCount(0)
                .build());
        DeadLetterReplayService replayService =
                new DeadLetterReplayService(deadLetterItemRepository, bus, objectMapper, CLOCK);

        replayService.replay(item.getId(), "ops.alice");

        // The replay republished the stored envelope with its original id and the registry
        // recognised it, so the effect was not applied a second time – and the item still
        // records that the replay happened.
        assertThat(handled).hasSize(1);
        assertThat(handled.get(0).eventId()).isEqualTo(original.eventId());
        DeadLetterEntry replayed = deadLetterItemRepository.findById(item.getId()).orElseThrow();
        assertThat(replayed.getStatus()).isEqualTo(DeadLetterStatus.REPLAYED);
        assertThat(replayed.getReplayCount()).isEqualTo(1);
        assertThat(replayed.getReplayedBy()).isEqualTo("ops.alice");
    }

    private String serialise(BusinessEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The registry as a bean, so the subscriber uses its real, transactional implementation. */
    @TestConfiguration(proxyBeanMethods = false)
    static class SubscriberTestConfig {

        @Bean
        Clock clock() {
            return CLOCK;
        }
    }
}
