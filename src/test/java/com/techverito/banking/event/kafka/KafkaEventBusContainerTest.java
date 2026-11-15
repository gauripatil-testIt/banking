package com.techverito.banking.event.kafka;

import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.EventType;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of the Kafka adapter against a real broker started by
 * Testcontainers: an event published through {@link BusinessEventBus#publish} is
 * consumed by the adapter's listener and handed to the handler registered through
 * {@link BusinessEventBus#subscribe}, carrying the same event id.
 *
 * <p>It needs Docker and nothing else – no external broker, no real credentials,
 * no network beyond the container. The test is skipped when Docker is not
 * available, exactly as the S3 LocalStack test is, so the rest of the suite still
 * runs without it.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class KafkaEventBusContainerTest {

    private static final String TOPIC = "banking.business-events";

    private static final String CONSUMER_GROUP = "banking-event-subscribers";

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final long DELIVERY_TIMEOUT_SECONDS = 60L;

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @BeforeAll
    static void createTopic() throws Exception {
        Map<String, Object> adminConfig =
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(adminConfig)) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1)))
                    .all()
                    .get(60, TimeUnit.SECONDS);
        }
    }

    /**
     * Publishes the same event until the subscriber has received it. The first
     * publications may be sent before the consumer group has been assigned its
     * partition, so republishing is what makes the test independent of consumer
     * startup timing; the assertion is about the event that arrives, not how often
     * it was published.
     */
    private static boolean publishUntilDelivered(BusinessEventBus bus, BusinessEvent event,
                                                 CountDownLatch delivered) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DELIVERY_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            bus.publish(event);
            if (delivered.await(2, TimeUnit.SECONDS)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void subscriber_receivesTheEventPublishedThroughTheRealBroker() {
        BusinessEvent published = BusinessEvent.create(EventType.STATEMENT_GENERATED, 5L, null,
                "ops.user", "corr-container-1", CLOCK);

        new ApplicationContextRunner()
                .withUserConfiguration(KafkaEventConfig.class, KafkaEventBus.class)
                .withPropertyValues(
                        "banking.events.transport=kafka",
                        "banking.events.topic=" + TOPIC,
                        "banking.events.consumer-group=" + CONSUMER_GROUP,
                        "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers())
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    BusinessEventBus bus = context.getBean(BusinessEventBus.class);
                    List<BusinessEvent> received = new CopyOnWriteArrayList<>();
                    CountDownLatch delivered = new CountDownLatch(1);
                    bus.subscribe(EventType.STATEMENT_GENERATED, event -> {
                        received.add(event);
                        delivered.countDown();
                    });

                    boolean arrived = publishUntilDelivered(bus, published, delivered);
                    assertThat(arrived)
                            .as("the subscriber registered through subscribe(...) receives the published event")
                            .isTrue();

                    assertThat(received).allSatisfy(event -> {
                        assertThat(event.eventId()).isEqualTo(published.eventId());
                        assertThat(event.type()).isEqualTo(EventType.STATEMENT_GENERATED);
                        assertThat(event.version()).isEqualTo(BusinessEvent.CURRENT_VERSION);
                        assertThat(event.occurredAt()).isEqualTo(NOW);
                        assertThat(event.customerId()).isEqualTo(5L);
                        assertThat(event.accountId()).isNull();
                        assertThat(event.actor()).isEqualTo("ops.user");
                        assertThat(event.correlationId()).isEqualTo("corr-container-1");
                    });
                });
    }
}
