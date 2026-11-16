package com.techverito.banking.event.kafka;

import com.techverito.banking.config.EventProperties;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.DeadLetterPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Dead-letters an event that could not be published to the dead-letter topic derived
 * from the configured topic – {@code banking.events.topic} plus {@code .DLT}, so the
 * topic convention stays one setting – and writes one structured ERROR report carrying
 * the event id, its type, the business key, the topic and the failure reason.
 *
 * <p>The dead-lettered record is keyed by the same business key as the original event,
 * so one account's failures can be read in order on one partition. The original outbox
 * row is never deleted; this topic is how the failure is announced, not where the only
 * copy of it lives.</p>
 */
@Component
@ConditionalOnProperty(prefix = "banking.events", name = "transport", havingValue = "kafka", matchIfMissing = true)
public class KafkaDeadLetterPublisher implements DeadLetterPublisher {

    static final String DEAD_LETTER_TOPIC_SUFFIX = ".DLT";

    static final String CUSTOMER_KEY_PREFIX = "customer:";

    static final String ACCOUNT_KEY_PREFIX = "account:";

    private static final Logger log = LoggerFactory.getLogger(KafkaDeadLetterPublisher.class);

    private final KafkaTemplate<String, BusinessEvent> kafkaTemplate;
    private final EventProperties properties;

    public KafkaDeadLetterPublisher(KafkaTemplate<String, BusinessEvent> kafkaTemplate,
                                    EventProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    /** The dead-letter topic of a topic: the topic's own name plus {@code .DLT}. */
    public static String deadLetterTopicName(String topic) {
        return topic + DEAD_LETTER_TOPIC_SUFFIX;
    }

    @Override
    public void deadLetter(BusinessEvent event, String reason) {
        String topic = deadLetterTopicName(properties.topic());
        String key = businessKey(event);
        log.error("A business event could not be published and was dead-lettered: eventId={}, type={}, "
                        + "businessKey={}, topic={}, reason={}",
                event.eventId(), event.type(), key, topic, reason);
        try {
            kafkaTemplate.send(topic, key, event).whenComplete((result, failure) -> {
                if (failure != null) {
                    log.warn("The dead-letter record for eventId={} could not be written to {}: {}",
                            event.eventId(), topic, reason(failure));
                }
            });
        } catch (RuntimeException ex) {
            // The event is still retained in the outbox with its reason; a dead-letter
            // topic that is unreachable must not fail the drain.
            log.warn("The dead-letter record for eventId={} could not be written to {}: {}",
                    event.eventId(), topic, reason(ex));
        }
    }

    /** The same ordering key the original event was published with: account first. */
    private String businessKey(BusinessEvent event) {
        if (event.accountId() != null) {
            return ACCOUNT_KEY_PREFIX + event.accountId();
        }
        return CUSTOMER_KEY_PREFIX + event.customerId();
    }

    private String reason(Throwable failure) {
        String message = failure.getMessage();
        return (message != null && !message.isBlank()) ? message : failure.getClass().getSimpleName();
    }
}
