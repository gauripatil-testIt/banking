package com.techverito.banking.event.consumer;

import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.EventType;
import com.techverito.banking.service.ProcessedEventRegistry;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * The seam every consumer handler goes through, so duplicate-safe handling is not something
 * each handler has to remember: the handler registered here is wrapped in
 * {@link ProcessedEventRegistry#runOnce}, which checks the event id before any handler code
 * runs, whichever transport delivered the event.
 *
 * <p>That is what makes a replay safe: a replayed event carries its original id, so an event
 * whose effect already ran is recognised and skipped instead of sending the same email twice.</p>
 *
 * <p>No broker type appears here: registration goes through {@link BusinessEventBus}.</p>
 */
@Component
public class IdempotentBusinessEventSubscriber {

    private final ProcessedEventRegistry processedEventRegistry;

    public IdempotentBusinessEventSubscriber(ProcessedEventRegistry processedEventRegistry) {
        this.processedEventRegistry = processedEventRegistry;
    }

    /**
     * Registers the handler for one event type, so it runs at most once per event id.
     */
    public void subscribe(BusinessEventBus bus, EventType type, Consumer<BusinessEvent> handler) {
        Objects.requireNonNull(bus, "bus");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(handler, "handler");
        bus.subscribe(type, event -> processedEventRegistry.runOnce(event, () -> handler.accept(event)));
    }
}
