package com.techverito.banking.service;

import com.techverito.banking.entity.ProcessedEvent;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.EventType;
import com.techverito.banking.repository.ProcessedEventRepository;
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
 * The processed-event registry against the real database: the same event id runs its effect
 * once, the marker keeps that id, its type and when it ran, and a handler that throws leaves no
 * marker behind so the event is handled again on its next delivery. No broker and no Docker are
 * involved – the registry is about the event's identity, not about who delivered it.
 *
 * <p>The test is non-transactional, so the registry's own transaction really commits or rolls
 * back the way the application's handler would: a marker left behind by a failed handler would
 * be visible here instead of being hidden by the test's rollback.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ProcessedEventRegistry.class, ProcessedEventRegistryTest.RegistryTestConfig.class})
class ProcessedEventRegistryTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Autowired
    ProcessedEventRepository processedEventRepository;

    @Autowired
    ProcessedEventRegistry registry;

    @BeforeEach
    void clearRegistry() {
        processedEventRepository.deleteAll();
    }

    private BusinessEvent event(EventType type) {
        return BusinessEvent.create(type, 1L, null, "ops.user", "corr-processed", CLOCK);
    }

    @Test
    void runOnce_runsTheEffectOnlyTheFirstTimeForAnEventIdAndRecordsOneMarker() {
        BusinessEvent event = event(EventType.CUSTOMER_REGISTERED);
        List<String> effects = new ArrayList<>();

        assertThat(registry.runOnce(event, () -> effects.add("sent the welcome email"))).isTrue();
        assertThat(registry.runOnce(event, () -> effects.add("sent the welcome email"))).isFalse();

        assertThat(effects).containsExactly("sent the welcome email");
        assertThat(processedEventRepository.existsByEventId(event.eventId())).isTrue();
        assertThat(processedEventRepository.findAll()).hasSize(1);
    }

    @Test
    void runOnce_storesTheMarkerWithTheEventIdItsTypeAndWhenItRan() {
        BusinessEvent event = event(EventType.DOCUMENT_UPLOADED);

        registry.runOnce(event, () -> { });

        List<ProcessedEvent> markers = processedEventRepository.findAll();
        assertThat(markers).hasSize(1);
        ProcessedEvent marker = markers.get(0);
        assertThat(marker.getEventId()).isEqualTo(event.eventId());
        assertThat(marker.getEventType()).isEqualTo(EventType.DOCUMENT_UPLOADED);
        assertThat(marker.getProcessedAt()).isEqualTo(NOW);
    }

    @Test
    void runOnce_whenTheEffectThrows_leavesNoMarkerBehindAndRunsTheEffectAgainNextTime() {
        BusinessEvent event = event(EventType.ACCOUNT_OPENED);
        List<String> attempts = new ArrayList<>();

        assertThatThrownBy(() -> registry.runOnce(event, () -> {
            attempts.add("attempted");
            throw new IllegalStateException("the effect could not be applied");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("the effect could not be applied");

        // The marker and the effect share one transaction, so the failed effect left no marker.
        assertThat(processedEventRepository.existsByEventId(event.eventId())).isFalse();
        assertThat(processedEventRepository.findAll()).isEmpty();

        // Without a marker the next delivery is not treated as a duplicate, so it runs again.
        assertThat(registry.runOnce(event, () -> attempts.add("attempted"))).isTrue();

        assertThat(attempts).containsExactly("attempted", "attempted");
        assertThat(processedEventRepository.findAll()).hasSize(1);
    }

    /**
     * The registry as a bean, so its own {@code @Transactional} boundary is the one the
     * assertions above observe, with a fixed clock instead of the wall clock.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class RegistryTestConfig {

        @Bean
        Clock clock() {
            return CLOCK;
        }
    }
}
