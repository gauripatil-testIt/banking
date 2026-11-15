package com.techverito.banking.event.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.techverito.banking.config.EventProperties;
import com.techverito.banking.config.EventTransport;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/**
 * The Kafka adapter against a mocked template: which topic and key an event is
 * published with, and how a consumed event is dispatched to the handlers. No
 * broker, no Docker and no network are involved – the real broker round trip is
 * covered by {@link KafkaEventBusContainerTest}.
 */
@ExtendWith(MockitoExtension.class)
class KafkaEventBusTest {

    private static final String TOPIC = "banking.business-events";

    private static final String CONSUMER_GROUP = "banking-event-subscribers";

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    KafkaTemplate<String, BusinessEvent> kafkaTemplate;

    private KafkaEventBus bus;

    @BeforeEach
    void setUp() {
        bus = new KafkaEventBus(kafkaTemplate, new EventProperties(EventTransport.KAFKA, TOPIC, CONSUMER_GROUP));
    }

    private BusinessEvent event(EventType type, Long customerId, Long accountId, String correlationId) {
        return BusinessEvent.create(type, customerId, accountId, "ops.user", correlationId, CLOCK);
    }

    @Test
    void publish_sendsToTheConfiguredTopicKeyedByCustomer() {
        BusinessEvent published = event(EventType.CUSTOMER_REGISTERED, 1L, null, "corr-1");

        bus.publish(published);

        verify(kafkaTemplate).send(TOPIC, "customer:1", published);
    }

    @Test
    void publish_accountOnlyEvent_isKeyedByAccount() {
        BusinessEvent published = event(EventType.ACCOUNT_OPENED, null, 9L, "corr-2");

        bus.publish(published);

        verify(kafkaTemplate).send(TOPIC, "account:9", published);
    }

    @Test
    void publish_eventNamingCustomerAndAccount_isKeyedByCustomer() {
        BusinessEvent published = event(EventType.ACCOUNT_STATUS_CHANGED, 4L, 12L, "corr-3");

        bus.publish(published);

        verify(kafkaTemplate).send(TOPIC, "customer:4", published);
    }

    @Test
    void onEvent_dispatchesOnlyToHandlersOfItsOwnType() {
        List<String> accountOpened = new ArrayList<>();
        List<String> documentUploaded = new ArrayList<>();
        bus.subscribe(EventType.ACCOUNT_OPENED, event -> accountOpened.add(event.eventId()));
        bus.subscribe(EventType.DOCUMENT_UPLOADED, event -> documentUploaded.add(event.eventId()));

        BusinessEvent consumed = event(EventType.ACCOUNT_OPENED, 1L, null, "corr-4");
        bus.onEvent(consumed);

        assertThat(accountOpened).containsExactly(consumed.eventId());
        assertThat(documentUploaded).isEmpty();
    }

    @Test
    void onEvent_notifiesEveryHandlerOfTheTypeInSubscriptionOrder() {
        List<String> handlers = new ArrayList<>();
        bus.subscribe(EventType.EXPORT_REQUESTED, event -> handlers.add("first"));
        bus.subscribe(EventType.EXPORT_REQUESTED, event -> handlers.add("second"));

        bus.onEvent(event(EventType.EXPORT_REQUESTED, 2L, null, "corr-5"));

        assertThat(handlers).containsExactly("first", "second");
    }

    @Test
    void onEvent_failingHandler_isLoggedWithEventIdTypeAndCorrelationAndTheOthersStillRun() {
        List<String> delivered = new ArrayList<>();
        bus.subscribe(EventType.DOCUMENT_UPLOADED, event -> {
            throw new IllegalStateException("handler failed");
        });
        bus.subscribe(EventType.DOCUMENT_UPLOADED, event -> delivered.add(event.eventId()));

        BusinessEvent consumed = event(EventType.DOCUMENT_UPLOADED, 7L, null, "corr-7");
        Logger logger = (Logger) LoggerFactory.getLogger(KafkaEventBus.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> bus.onEvent(consumed))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("handler failed");

            assertThat(delivered).containsExactly(consumed.eventId());

            assertThat(appender.list).hasSize(1);
            ILoggingEvent logEvent = appender.list.get(0);
            assertThat(logEvent.getLevel()).isEqualTo(Level.ERROR);
            assertThat(logEvent.getFormattedMessage())
                    .contains(consumed.eventId())
                    .contains("DOCUMENT_UPLOADED")
                    .contains("corr-7");
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void onEvent_withNoSubscribers_doesNothing() {
        assertThatCode(() -> bus.onEvent(event(EventType.STATEMENT_GENERATED, 3L, null, "corr-8")))
                .doesNotThrowAnyException();
    }
}
