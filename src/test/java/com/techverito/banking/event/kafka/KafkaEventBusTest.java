package com.techverito.banking.event.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.techverito.banking.config.EventProperties;
import com.techverito.banking.config.EventTransport;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.EventPublishException;
import com.techverito.banking.event.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Kafka adapter against a mocked template: which topic and key an event is
 * published with, that a publish only returns once the broker acknowledged it, and
 * how a consumed event is dispatched to the handlers. No broker, no Docker and no
 * network are involved – the real broker round trip is covered by
 * {@link KafkaEventBusContainerTest}.
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

    /** The broker's acknowledgement, so a publish under test completes instead of timing out. */
    private static CompletableFuture<SendResult<String, BusinessEvent>> acknowledged() {
        return CompletableFuture.completedFuture(null);
    }

    /** Stubs the producer to acknowledge a send of the given event under the given key. */
    private void givenBrokerAcknowledges(String key, BusinessEvent event) {
        when(kafkaTemplate.send(TOPIC, key, event)).thenReturn(acknowledged());
    }

    @Test
    void publish_sendsToTheConfiguredTopicKeyedByCustomer() {
        BusinessEvent published = event(EventType.CUSTOMER_REGISTERED, 1L, null, "corr-1");
        givenBrokerAcknowledges("customer:1", published);

        bus.publish(published);

        verify(kafkaTemplate).send(TOPIC, "customer:1", published);
    }

    @Test
    void publish_accountOnlyEvent_isKeyedByAccount() {
        BusinessEvent published = event(EventType.ACCOUNT_OPENED, null, 9L, "corr-2");
        givenBrokerAcknowledges("account:9", published);

        bus.publish(published);

        verify(kafkaTemplate).send(TOPIC, "account:9", published);
    }

    /**
     * An event naming both a customer and an account is keyed by the account: the
     * ordering guarantee is per account, so the account decides the partition.
     */
    @Test
    void publish_eventNamingCustomerAndAccount_isKeyedByCustomer() {
        BusinessEvent published = event(EventType.ACCOUNT_STATUS_CHANGED, 4L, 12L, "corr-3");
        givenBrokerAcknowledges("account:12", published);

        bus.publish(published);

        verify(kafkaTemplate).send(TOPIC, "account:12", published);
    }

    @Test
    void publish_failsWithEventIdAndReasonWhenTheBrokerDoesNotAcknowledge() {
        BusinessEvent published = event(EventType.ACCOUNT_OPENED, 2L, 9L, "corr-9");
        CompletableFuture<SendResult<String, BusinessEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new TimeoutException("the broker did not acknowledge the record"));
        when(kafkaTemplate.send(TOPIC, "account:9", published)).thenReturn(failed);

        assertThatThrownBy(() -> bus.publish(published))
                .isInstanceOf(EventPublishException.class)
                .hasMessageContaining(published.eventId())
                .hasMessageContaining("the broker did not acknowledge the record")
                .hasCauseInstanceOf(TimeoutException.class);
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
