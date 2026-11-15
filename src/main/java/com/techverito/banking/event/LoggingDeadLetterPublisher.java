package com.techverito.banking.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dead-letters an event by reporting it at ERROR, for the in-memory transport, which
 * has no broker by design and therefore no dead-letter topic to write to.
 *
 * <p>It performs no network call at all, so an application started with
 * {@code banking.events.transport=in-memory} still starts and runs while nothing is
 * listening: the stored outbox row keeps the event, and the log says which one failed
 * and why.</p>
 */
@Component
@ConditionalOnProperty(prefix = "banking.events", name = "transport", havingValue = "in-memory")
public class LoggingDeadLetterPublisher implements DeadLetterPublisher {

    static final String CUSTOMER_KEY_PREFIX = "customer:";

    static final String ACCOUNT_KEY_PREFIX = "account:";

    private static final Logger log = LoggerFactory.getLogger(LoggingDeadLetterPublisher.class);

    @Override
    public void deadLetter(BusinessEvent event, String reason) {
        log.error("A business event could not be published and was dead-lettered: eventId={}, type={}, "
                        + "businessKey={}, reason={}",
                event.eventId(), event.type(), businessKey(event), reason);
    }

    /** The same ordering key the original event was published with: account first. */
    private String businessKey(BusinessEvent event) {
        if (event.accountId() != null) {
            return ACCOUNT_KEY_PREFIX + event.accountId();
        }
        return CUSTOMER_KEY_PREFIX + event.customerId();
    }
}
