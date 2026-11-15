package com.techverito.banking.event.kafka;

import com.techverito.banking.config.EventProperties;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The broker-backed bus: publishes every business event to one Kafka topic and
 * dispatches the events consumed from that topic to the handlers registered through
 * {@link #subscribe(EventType, Consumer)}.
 *
 * <p>An event is keyed by what it concerns – {@code customer:{customerId}} when a
 * customer is named, {@code account:{accountId}} otherwise – so the events of one
 * customer stay in order on one partition.</p>
 *
 * <p>Delivery is deliberately plain: no retry chain, no dead-letter topic and no
 * deduplication by event id. Reliable delivery is a later story; a handler that
 * fails is logged and the container decides what to do with the record, exactly as
 * with any other listener in this application.</p>
 */
@Component
@ConditionalOnProperty(prefix = "banking.events", name = "transport", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventBus implements BusinessEventBus {

    static final String CUSTOMER_KEY_PREFIX = "customer:";

    static final String ACCOUNT_KEY_PREFIX = "account:";

    private static final Logger log = LoggerFactory.getLogger(KafkaEventBus.class);

    private final KafkaTemplate<String, BusinessEvent> kafkaTemplate;
    private final EventProperties properties;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public KafkaEventBus(KafkaTemplate<String, BusinessEvent> kafkaTemplate, EventProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @Override
    public void publish(BusinessEvent event) {
        Objects.requireNonNull(event, "event");
        String key = businessKey(event);
        try {
            kafkaTemplate.send(properties.topic(), key, event);
        } catch (RuntimeException ex) {
            log.error("Could not publish a business event: eventId={}, type={}, correlationId={}, topic={}, key={}",
                    event.eventId(), event.type(), event.correlationId(), properties.topic(), key, ex);
            throw ex;
        }
    }

    @Override
    public void subscribe(EventType type, Consumer<BusinessEvent> handler) {
        subscriptions.add(new Subscription(Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(handler, "handler")));
    }

    /**
     * Receives every event published to the configured topic and hands it to the
     * handlers registered for its type. A handler that fails is logged and the
     * remaining handlers for the same event still run; the failure is rethrown
     * afterwards, so the record is not treated as processed.
     */
    @KafkaListener(topics = "${banking.events.topic:banking.business-events}",
            groupId = "${banking.events.consumer-group:banking-event-subscribers}")
    public void onEvent(BusinessEvent event) {
        RuntimeException failure = null;
        for (Subscription subscription : subscriptions) {
            if (subscription.type() != event.type()) {
                continue;
            }
            try {
                subscription.handler().accept(event);
            } catch (RuntimeException ex) {
                log.error("A business event handler failed: eventId={}, type={}, correlationId={}",
                        event.eventId(), event.type(), event.correlationId(), ex);
                if (failure == null) {
                    failure = ex;
                } else {
                    failure.addSuppressed(ex);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        log.debug("Consumed business event: eventId={}, type={}, correlationId={}",
                event.eventId(), event.type(), event.correlationId());
    }

    private String businessKey(BusinessEvent event) {
        if (event.customerId() != null) {
            return CUSTOMER_KEY_PREFIX + event.customerId();
        }
        return ACCOUNT_KEY_PREFIX + event.accountId();
    }

    /** One handler registered for one event type. */
    private record Subscription(EventType type, Consumer<BusinessEvent> handler) {
    }
}
