package com.techverito.banking.service;

import com.techverito.banking.entity.ProcessedEvent;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/**
 * The framework-level registry that makes handling an event duplicate-safe: the effect of a
 * handler runs only for an {@code eventId} this application has not already handled.
 *
 * <p>The marker and the effect are written in <em>one</em> transaction, so they commit or roll
 * back together: a handler that throws leaves no marker behind and its event is handled again
 * on the next delivery instead of being skipped as done. The unique {@code event_id} on the
 * marker is the backstop for two deliveries racing each other – the loser cannot commit a
 * second effect.</p>
 *
 * <p>No broker type appears here: this is about the event's identity, whatever transport
 * delivered it.</p>
 */
@Service
public class ProcessedEventRegistry {

    private final ProcessedEventRepository processedEventRepository;
    private final Clock clock;

    public ProcessedEventRegistry(ProcessedEventRepository processedEventRepository, Clock clock) {
        this.processedEventRepository = processedEventRepository;
        this.clock = clock;
    }

    /**
     * Runs the effect for the event unless this event id has already been handled.
     *
     * @return {@code true} when the effect ran, {@code false} when the event was already
     *         handled and nothing was done
     */
    @Transactional
    public boolean runOnce(BusinessEvent event, Runnable effect) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(effect, "effect");
        if (processedEventRepository.existsByEventId(event.eventId())) {
            return false;
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(event.eventId())
                .eventType(event.type())
                .processedAt(clock.instant())
                .build());
        effect.run();
        return true;
    }
}
