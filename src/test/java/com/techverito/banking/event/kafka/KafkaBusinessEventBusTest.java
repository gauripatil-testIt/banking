package com.techverito.banking.event.kafka;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.config.EventBusProperties;
import com.techverito.banking.config.EventBusType;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventJson;
import com.techverito.banking.event.BusinessEventType;
import com.techverito.banking.event.CustomerRegisteredPayload;
import com.techverito.banking.event.SubjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Kafka adapter is proved with a mocked template: no broker, no Docker and no
 * network access are involved. What matters here is the message it produces, the topic
 * it writes to, and that a broker failure can never escape into business code.
 */
@ExtendWith(MockitoExtension.class)
class KafkaBusinessEventBusTest {

    private static final UUID EVENT_ID = UUID.fromString("3f1d2c9a-8b7e-4c6d-9f01-2a3b4c5d6e7f");

    private static final Instant OCCURRED_AT = Instant.parse("2025-03-04T09:07:45Z");

    private static final String CORRELATION_ID = "corr-2025-03-04-0001";

    private static final String TOPIC = "banking.events";

    @Mock
    KafkaTemplate<String, String> kafkaTemplate;

    private final BusinessEventJson json = new BusinessEventJson();

    private KafkaBusinessEventBus bus() {
        return new KafkaBusinessEventBus(kafkaTemplate, json,
                new EventBusProperties(EventBusType.KAFKA, TOPIC));
    }

    private static BusinessEvent<CustomerRegisteredPayload> customerRegistered() {
        return new BusinessEvent<>(EVENT_ID, BusinessEventType.CUSTOMER_REGISTERED.getWireValue(),
                BusinessEventType.CUSTOMER_REGISTERED.getCurrentVersion(), OCCURRED_AT, SubjectType.CUSTOMER, 1L,
                "ops.user", CORRELATION_ID,
                new CustomerRegisteredPayload(1L, "John", "Doe", "john@example.com"));
    }

    @Test
    void sendsTheDocumentedJsonToTheConfiguredTopic() throws Exception {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
        BusinessEvent<CustomerRegisteredPayload> event = customerRegistered();

        bus().publish(event);

        ArgumentCaptor<String> jsonSent = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq("CUSTOMER-1"), jsonSent.capture());

        assertThat(jsonSent.getValue()).isEqualTo(json.toJson(event));

        JsonNode node = new ObjectMapper().readTree(jsonSent.getValue());
        assertThat(node.get("eventId").textValue()).isEqualTo(EVENT_ID.toString());
        assertThat(node.get("type").textValue()).isEqualTo("customer.registered");
        assertThat(node.get("version").intValue()).isEqualTo(1);
        assertThat(node.get("occurredAt").textValue()).isEqualTo("2025-03-04T09:07:45Z");
        assertThat(node.get("subjectType").textValue()).isEqualTo("CUSTOMER");
        assertThat(node.get("subjectId").longValue()).isEqualTo(1L);
        assertThat(node.get("causedBy").textValue()).isEqualTo("ops.user");
        assertThat(node.get("correlationId").textValue()).isEqualTo(CORRELATION_ID);
        assertThat(node.get("payload").get("email").textValue()).isEqualTo("john@example.com");
    }

    @Test
    void sendFailureIsLoggedAndNotRethrown() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("broker is unavailable"));
        BusinessEvent<CustomerRegisteredPayload> event = customerRegistered();

        Logger busLogger = (Logger) LoggerFactory.getLogger(KafkaBusinessEventBus.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        busLogger.addAppender(appender);
        try {
            assertThatCode(() -> bus().publish(event)).doesNotThrowAnyException();

            assertThat(appender.list).anySatisfy(logged -> {
                assertThat(logged.getLevel().toString()).isEqualTo("ERROR");
                assertThat(logged.getFormattedMessage())
                        .contains(EVENT_ID.toString())
                        .contains("customer.registered")
                        .contains(CORRELATION_ID)
                        .doesNotContain("john@example.com")
                        .doesNotContain("John Doe");
            });
        } finally {
            busLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void subscribeIsNotSupportedYet() {
        assertThatThrownBy(() -> bus().subscribe(BusinessEventType.CUSTOMER_REGISTERED, 1,
                CustomerRegisteredPayload.class, event -> { }))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("BANK-9");
    }
}
