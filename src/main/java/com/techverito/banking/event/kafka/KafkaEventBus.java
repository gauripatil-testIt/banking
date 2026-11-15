package com.techverito.banking.event.kafka;

import com.techverito.banking.config.EventProperties;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.EventPublishException;
import com.techverito.banking.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * The broker-backed bus: publishes every business event to one Kafka topic and
 * dispatches the events consumed from that topic to the handlers registered through
 * {@link #subscribe(EventType, Consumer)}.
 *
 * <p>An event is keyed by what it concerns – {@code account:{accountId}} whenever it
 * names an account, {@code customer:{customerId}} only for a customer-only event – so
 * the events of one account stay in order on one partition, while different accounts
 * spread over different partitions and never queue behind each other.</p>
 *
 * <p>Publishing is not fire-and-forget: {@link #publish(BusinessEvent)} returns only
 * after Kafka acknowledged the record, and fails with an
 * {@link EventPublishException} naming the event id when the broker did not store it,
 * so the caller can retry instead of losing the event.</p>
 *
 * <p>Consumption is deliberately plain: no retry chain, no dead-letter handling and no
 * deduplication by event id on the consumer side. A handler that fails is logged and
 * the container decides what to do with the record, exactly as with any other listener
 * in this application.</p>
 */
@Component
@ConditionalOnProperty(prefix = "banking.events", name = "transport", havingValue = "kafka", matchIfMissing = true)
public class KafkaEventBus implements BusinessEventBus {

    static final String CUSTOMER_KEY_PREFIX = "customer:";

    static final String ACCOUNT_KEY_PREFIX = "account:";

    /** How long a publish waits for the broker's acknowledgement before failing the event. */
    static final Duration ACK_TIMEOUT = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(KafkaEventBus.class);

    private final KafkaTemplate<String, BusinessEvent> kafkaTemplate;
    private final EventProperties properties;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    public KafkaEventBus(KafkaTemplate<String, BusinessEvent> kafkaTemplate, EventProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    /**
     * Publishes the event and waits for Kafka's acknowledgement. A send that fails or
     * is not acknowledged within {@link #ACK_TIMEOUT} throws an
     * {@link EventPublishException} naming the event id, so the caller can retry it.
     */
    @Override
    public void publish(BusinessEvent event) {
        Objects.requireNonNull(event, "event");
        String key = businessKey(event);
        CompletableFuture<SendResult<String, BusinessEvent>> send;
        try {
            send = kafkaTemplate.send(properties.topic(), key, event);
        } catch (RuntimeException ex) {
            log.error("Could not publish a business event: eventId={}, type={}, correlationId={}, topic={}, key={}",
                    event.eventId(), event.type(), event.correlationId(), properties.topic(), key, ex);
            throw new EventPublishException(event.eventId(), describe(ex), ex);
        }
        try {
            send.get(ACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while waiting for Kafka to acknowledge a business event: eventId={}, type={}, "
                    + "correlationId={}, topic={}, key={}", event.eventId(), event.type(), event.correlationId(),
                    properties.topic(), key, ex);
            throw new EventPublishException(event.eventId(),
                    "the wait for Kafka's acknowledgement was interrupted", ex);
        } catch (ExecutionException | TimeoutException ex) {
            Throwable cause = (ex.getCause() != null) ? ex.getCause() : ex;
            log.error("Kafka did not acknowledge a business event: eventId={}, type={}, correlationId={}, topic={}, key={}",
                    event.eventId(), event.type(), event.correlationId(), properties.topic(), key, cause);
            throw new EventPublishException(event.eventId(),
                    "Kafka did not acknowledge it within " + ACK_TIMEOUT.toSeconds() + "s: " + describe(cause), cause);
        }
        log.debug("Kafka acknowledged business event: eventId={}, type={}, correlationId={}, topic={}, key={}",
                event.eventId(), event.type(), event.correlationId(), properties.topic(), key);
    }

    private String describe(Throwable failure) {
        String message = failure.getMessage();
        return (message != null && !message.isBlank()) ? message : failure.getClass().getSimpleName();
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

    /**
     * The ordering key: an event that names an account is keyed by the account, so the
     * events of one account keep their order; only a customer-only event is keyed by
     * the customer.
     */
    private String businessKey(BusinessEvent event) {
        if (event.accountId() != null) {
            return ACCOUNT_KEY_PREFIX + event.accountId();
        }
        return CUSTOMER_KEY_PREFIX + event.customerId();
    }

    /** One handler registered for one event type. */
    private record Subscription(EventType type, Consumer<BusinessEvent> handler) {
    }
}
