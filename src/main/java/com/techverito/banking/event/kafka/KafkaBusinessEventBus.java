package com.techverito.banking.event.kafka;

import com.techverito.banking.config.EventBusProperties;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.BusinessEventHandler;
import com.techverito.banking.event.BusinessEventJson;
import com.techverito.banking.event.BusinessEventType;
import com.techverito.banking.event.EventPayload;
import com.techverito.banking.event.EventSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * The real broker side of the business event bus: it writes the documented JSON of an
 * event to {@code banking.events.topic} and does not wait for the broker to acknowledge
 * it. This is the only class that knows Kafka exists, so business code stays on
 * {@link BusinessEventBus}.
 *
 * <p>Sending is fire-and-forget: a failure is logged with the event id, the type and the
 * correlation id - never a customer name or an email - and is not rethrown, so an event
 * announcement can never fail a business operation. Reliable delivery (outbox, retries,
 * dead-letter, consumption) is BANK-9 and is deliberately not built here.</p>
 */
@Component
@ConditionalOnProperty(prefix = "banking.events", name = "bus", havingValue = "kafka", matchIfMissing = true)
public class KafkaBusinessEventBus implements BusinessEventBus {

    private static final Logger log = LoggerFactory.getLogger(KafkaBusinessEventBus.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final BusinessEventJson json;
    private final EventBusProperties properties;

    public KafkaBusinessEventBus(KafkaTemplate<String, String> kafkaTemplate,
                                 BusinessEventJson json,
                                 EventBusProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.json = json;
        this.properties = properties;
    }

    @Override
    public void publish(BusinessEvent<? extends EventPayload> event) {
        String topic = properties.topic();
        String key = subjectKey(event);
        String payload = json.toJson(event);
        try {
            CompletableFuture<SendResult<String, String>> send = kafkaTemplate.send(topic, key, payload);
            if (send != null) {
                send.whenComplete((result, failure) -> {
                    if (failure != null) {
                        logSendFailure(event, topic, failure);
                    }
                });
            }
        } catch (RuntimeException ex) {
            logSendFailure(event, topic, ex);
        }
    }

    /**
     * Consuming events is not part of this story: a handler accepted here would never be
     * called, so it fails loudly instead of silently doing nothing.
     */
    @Override
    public <T extends EventPayload> EventSubscription subscribe(BusinessEventType type, int version,
                                                               Class<T> payloadType,
                                                               BusinessEventHandler<T> handler) {
        throw new UnsupportedOperationException(
                "Subscribing to business events over Kafka is not supported yet: consuming events is BANK-9. "
                        + "Use banking.events.bus=in-memory for a subscriber in this application.");
    }

    /**
     * The partition key: the business subject the event concerns, so every event about
     * one customer or one account lands on the same partition in order.
     */
    private String subjectKey(BusinessEvent<? extends EventPayload> event) {
        return event.subjectType() + "-" + event.subjectId();
    }

    private void logSendFailure(BusinessEvent<? extends EventPayload> event, String topic, Throwable failure) {
        log.error("Could not send the business event: eventId={}, type={}, correlationId={}, topic={}",
                event.eventId(), event.type(), event.correlationId(), topic, failure);
    }
}
