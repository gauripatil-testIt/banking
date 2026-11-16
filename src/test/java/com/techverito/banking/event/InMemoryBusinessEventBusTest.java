package com.techverito.banking.event;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The in-memory bus is the implementation the application and the rest of this suite run
 * against when no broker is installed, so these tests exercise it directly, without a
 * Spring context, without Docker and without network access.
 */
class InMemoryBusinessEventBusTest {

    private static final String WIRE_VALUE = BusinessEventType.CUSTOMER_REGISTERED.getWireValue();

    private static final Instant OCCURRED_AT = Instant.parse("2025-03-04T09:07:45Z");

    private final InMemoryBusinessEventBus bus = new InMemoryBusinessEventBus();

    private static BusinessEvent<CustomerRegisteredPayload> customerRegistered(int version, String correlationId) {
        return new BusinessEvent<>(UUID.randomUUID(), WIRE_VALUE, version, OCCURRED_AT, SubjectType.CUSTOMER, 1L,
                "ops.user", correlationId,
                new CustomerRegisteredPayload(1L, "John", "Doe", "john@example.com"));
    }

    /** Captures the bus log for the duration of one test, so the failure log can be asserted. */
    private ListAppender<ILoggingEvent> attachAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        busLogger().addAppender(appender);
        return appender;
    }

    private void detachAppender(ListAppender<ILoggingEvent> appender) {
        busLogger().detachAppender(appender);
        appender.stop();
    }

    private Logger busLogger() {
        return (Logger) LoggerFactory.getLogger(InMemoryBusinessEventBus.class);
    }

    @Test
    void deliversToEveryMatchingSubscriberInOrder() {
        List<String> received = new ArrayList<>();
        bus.subscribe(BusinessEventType.CUSTOMER_REGISTERED, 1, CustomerRegisteredPayload.class,
                event -> received.add("first:" + event.payload().email()));
        bus.subscribe(BusinessEventType.CUSTOMER_REGISTERED, 1, CustomerRegisteredPayload.class,
                event -> received.add("second:" + event.payload().email()));

        BusinessEvent<CustomerRegisteredPayload> published = customerRegistered(1, "corr-order-0001");
        bus.publish(published);

        assertThat(received).containsExactly("first:john@example.com", "second:john@example.com");
    }

    @Test
    void doesNotDeliverADifferentVersionToAVersionOneSubscriber() {
        List<BusinessEvent<CustomerRegisteredPayload>> received = new ArrayList<>();
        bus.subscribe(BusinessEventType.CUSTOMER_REGISTERED, 1, CustomerRegisteredPayload.class, received::add);

        bus.publish(customerRegistered(2, "corr-version-0002"));

        assertThat(received).isEmpty();
    }

    @Test
    void aThrowingSubscriberDoesNotBlockTheOthers() {
        ListAppender<ILoggingEvent> appender = attachAppender();
        try {
            List<BusinessEvent<CustomerRegisteredPayload>> received = new ArrayList<>();
            bus.subscribe(BusinessEventType.CUSTOMER_REGISTERED, 1, CustomerRegisteredPayload.class, event -> {
                throw new IllegalStateException("this handler explodes");
            });
            bus.subscribe(BusinessEventType.CUSTOMER_REGISTERED, 1, CustomerRegisteredPayload.class, received::add);

            BusinessEvent<CustomerRegisteredPayload> published = customerRegistered(1, "corr-throwing-0001");

            assertThatCode(() -> bus.publish(published)).doesNotThrowAnyException();
            assertThat(received).containsExactly(published);

            assertThat(appender.list).anySatisfy(logged -> {
                assertThat(logged.getLevel().toString()).isEqualTo("ERROR");
                assertThat(logged.getFormattedMessage())
                        .contains(published.eventId().toString())
                        .contains(published.type())
                        .contains(published.correlationId());
            });
        } finally {
            detachAppender(appender);
        }
    }

    @Test
    void closingASubscriptionStopsDelivery() {
        List<BusinessEvent<CustomerRegisteredPayload>> closedHandlerReceived = new ArrayList<>();
        List<BusinessEvent<CustomerRegisteredPayload>> openHandlerReceived = new ArrayList<>();
        EventSubscription closed = bus.subscribe(BusinessEventType.CUSTOMER_REGISTERED, 1,
                CustomerRegisteredPayload.class, closedHandlerReceived::add);
        bus.subscribe(BusinessEventType.CUSTOMER_REGISTERED, 1, CustomerRegisteredPayload.class,
                openHandlerReceived::add);

        closed.close();
        closed.close(); // closing twice is harmless

        bus.publish(customerRegistered(1, "corr-closed-0001"));

        assertThat(closedHandlerReceived).isEmpty();
        assertThat(openHandlerReceived).hasSize(1);
    }

    @Test
    void publishingWithoutSubscribersDoesNotThrow() {
        assertThatCode(() -> bus.publish(customerRegistered(1, "corr-nobody-0001")))
                .doesNotThrowAnyException();
    }
}
