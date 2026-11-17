package com.techverito.banking.event.outbox;

import com.techverito.banking.config.EventBusProperties;
import com.techverito.banking.config.EventBusType;
import com.techverito.banking.config.KafkaEventProperties;
import com.techverito.banking.config.KafkaProducerConfig;
import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.dto.CustomerResponse;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.OutboxEvent;
import com.techverito.banking.entity.OutboxEventStatus;
import com.techverito.banking.event.AccountStatusChangedPayload;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventFactory;
import com.techverito.banking.event.BusinessEventJson;
import com.techverito.banking.event.BusinessEventOutbox;
import com.techverito.banking.event.BusinessEventType;
import com.techverito.banking.event.CustomerRegisteredPayload;
import com.techverito.banking.event.SubjectType;
import com.techverito.banking.repository.CustomerRepository;
import com.techverito.banking.repository.OutboxEventRepository;
import com.techverito.banking.service.CustomerService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Criterion 5 of the story, against a real broker: a customer action must succeed while Kafka is
 * down, the event must wait in the outbox, and it must be delivered once the broker returns.
 *
 * <p>The broker is deliberately not started by the Testcontainers extension here - the container is
 * started by the test itself, in the middle of the outage - so the "no broker" phase is genuine. The
 * publishing path is the real {@link KafkaTemplate} from {@link KafkaProducerConfig} once the broker
 * is up; no stub publishes anything.</p>
 *
 * <p>Needs Docker, like the other {@code *IT} classes, so the unit suite still runs without a
 * daemon.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class OutboxOutageRecoveryIT {

    private static final String TOPIC = "banking.events";

    private static final Instant OCCURRED_AT = Instant.parse("2025-03-04T09:07:45Z");

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    CustomerRepository customerRepository;

    private final BusinessEventJson json = new BusinessEventJson();

    private final Clock clock = Clock.systemUTC();

    private KafkaContainer kafka;

    @BeforeEach
    void requireDockerAndACleanDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the real-Kafka outage test");
        outboxEventRepository.deleteAll();
        customerRepository.deleteAll();
        kafka = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));
    }

    @AfterEach
    void stopBroker() {
        if (kafka != null) {
            kafka.stop();
        }
    }

    private BusinessEventOutbox outboxWriter() {
        return new BusinessEventOutbox(outboxEventRepository, new BusinessEventFactory(clock), json,
                new EventBusProperties(EventBusType.KAFKA, TOPIC), clock);
    }

    private CustomerService customerService() {
        return new CustomerService(customerRepository, outboxWriter());
    }

    /**
     * A relay whose producer points at a port nothing listens on: a real Kafka client, real
     * connection failures, only the timeouts shortened so the test does not wait a minute per pass.
     */
    private OutboxRelay relayWithoutBroker(OutboxRelayProperties properties) {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
        config.put(ProducerConfig.CLIENT_ID_CONFIG, "outbox-it-no-broker");
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 1500);
        config.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
        config.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000);
        config.put(ProducerConfig.RETRIES_CONFIG, 0);
        KafkaTemplate<String, String> template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
        return new OutboxRelay(outboxEventRepository, template, properties, clock);
    }

    /** The relay as the application builds it: the real producer configuration, the real broker. */
    private OutboxRelay relayAgainstTheBroker(OutboxRelayProperties properties) {
        KafkaProducerConfig producerConfig = new KafkaProducerConfig();
        KafkaTemplate<String, String> template = producerConfig.kafkaTemplate(producerConfig.producerFactory(
                new KafkaEventProperties(kafka.getBootstrapServers(), "outbox-it-recovery")));
        return new OutboxRelay(outboxEventRepository, template, properties, clock);
    }

    private OutboxEvent reload(UUID eventId) {
        return outboxEventRepository.findAll().stream()
                .filter(row -> row.getEventId().equals(eventId))
                .findFirst()
                .orElseThrow();
    }

    private void makeDue(UUID eventId) {
        OutboxEvent row = reload(eventId);
        row.setNextAttemptAt(Instant.now().minusSeconds(1));
        outboxEventRepository.save(row);
    }

    private String statusChangedPayload(UUID eventId, long accountId) {
        return json.toJson(new BusinessEvent<>(eventId,
                BusinessEventType.ACCOUNT_STATUS_CHANGED.getWireValue(),
                BusinessEventType.ACCOUNT_STATUS_CHANGED.getCurrentVersion(),
                OCCURRED_AT, SubjectType.ACCOUNT, accountId, "ops.user", "corr-" + eventId,
                new AccountStatusChangedPayload(accountId, "00012345", AccountStatus.ACTIVE,
                        AccountStatus.CLOSED)));
    }

    private OutboxEvent storeStatusChange(long accountId, UUID eventId, String payload) {
        return outboxEventRepository.save(OutboxEvent.builder()
                .eventId(eventId)
                .eventType(BusinessEventType.ACCOUNT_STATUS_CHANGED.getWireValue())
                .eventVersion(BusinessEventType.ACCOUNT_STATUS_CHANGED.getCurrentVersion())
                .subjectType(SubjectType.ACCOUNT)
                .subjectId(accountId)
                .sequenceNo(1L)
                .orderingKey("ACCOUNT-" + accountId)
                .topic(TOPIC)
                .payload(payload)
                .createdAt(OCCURRED_AT)
                .nextAttemptAt(Instant.now().minusSeconds(60))
                .build());
    }

    /** Reads the topic from the beginning with a fresh consumer group, keeping the matching records. */
    private List<ConsumerRecord<String, String>> consumeMatching(Predicate<ConsumerRecord<String, String>> matches,
                                                                 int expected, Duration timeout) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-it-recovery-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        List<ConsumerRecord<String, String>> matching = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (matching.size() < expected && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (matches.test(record)) {
                        matching.add(record);
                    }
                });
            }
        }
        return matching;
    }

    @Test
    void businessActionSucceedsAndEventIsDeliveredWhenKafkaReturns() {
        // The business action runs with no broker at all and still succeeds.
        CustomerResponse created = customerService().create(new CustomerRequest("John", "Doe", "john@example.com",
                "1234567890", CustomerStatus.ACTIVE));
        String orderingKey = "CUSTOMER-" + created.id();

        assertThat(outboxEventRepository.findAll()).hasSize(1);
        OutboxEvent row = outboxEventRepository.findAll().get(0);
        assertThat(row.getEventType()).isEqualTo("customer.registered");
        assertThat(row.getTopic()).isEqualTo(TOPIC);
        assertThat(row.getOrderingKey()).isEqualTo(orderingKey);
        assertThat(row.getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        // A relay pass with no reachable broker cannot publish: the row survives, is counted and
        // stays PENDING, so nothing is lost while Kafka is down.
        relayWithoutBroker(new OutboxRelayProperties(50L, 2000L, 60_000L, 10)).relayPending();
        OutboxEvent stillWaiting = reload(row.getEventId());
        assertThat(stillWaiting.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(stillWaiting.getAttemptCount()).isEqualTo(1);
        assertThat(stillWaiting.getNextAttemptAt()).isAfter(Instant.now());

        // Kafka comes back: the same row is delivered to the real topic and then removed.
        kafka.start();
        makeDue(row.getEventId());
        relayAgainstTheBroker(new OutboxRelayProperties(50L, 2000L, 60_000L, 10)).relayPending();

        assertThat(outboxEventRepository.findAll()).isEmpty();

        List<ConsumerRecord<String, String>> records =
                consumeMatching(record -> orderingKey.equals(record.key()), 1, Duration.ofSeconds(30));
        assertThat(records).hasSize(1);

        BusinessEvent<CustomerRegisteredPayload> delivered =
                json.fromJson(records.get(0).value(), CustomerRegisteredPayload.class);
        assertThat(delivered.eventId()).isEqualTo(row.getEventId());
        assertThat(delivered.type()).isEqualTo("customer.registered");
        assertThat(delivered.version()).isEqualTo(1);
        assertThat(delivered.subjectType()).isEqualTo(SubjectType.CUSTOMER);
        assertThat(delivered.payload().email()).isEqualTo("john@example.com");
    }

    @Test
    void transientFailureIsRetriedWithBackoffThenDelivered() {
        long accountId = 4_242L;
        UUID eventId = UUID.randomUUID();
        storeStatusChange(accountId, eventId, statusChangedPayload(eventId, accountId));
        OutboxRelayProperties properties = new OutboxRelayProperties(50L, 5000L, 60_000L, 10);

        Instant beforeFirstAttempt = Instant.now();
        relayWithoutBroker(properties).relayPending();

        OutboxEvent afterFirstAttempt = reload(eventId);
        assertThat(afterFirstAttempt.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(afterFirstAttempt.getAttemptCount()).isEqualTo(1);
        assertThat(afterFirstAttempt.getNextAttemptAt()).isAfter(beforeFirstAttempt);
        Duration firstDelay = Duration.between(Instant.now(), afterFirstAttempt.getNextAttemptAt());
        assertThat(firstDelay).isGreaterThan(Duration.ZERO);

        // Not due yet: another pass leaves the row alone instead of hammering the broker.
        relayWithoutBroker(properties).relayPending();
        assertThat(reload(eventId).getAttemptCount()).isEqualTo(1);

        // The backoff interval passed: the retry happens and the delay grows (exponential backoff).
        makeDue(eventId);
        relayWithoutBroker(properties).relayPending();
        OutboxEvent afterSecondAttempt = reload(eventId);
        assertThat(afterSecondAttempt.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(afterSecondAttempt.getAttemptCount()).isEqualTo(2);
        Duration secondDelay = Duration.between(Instant.now(), afterSecondAttempt.getNextAttemptAt());
        assertThat(secondDelay).isGreaterThanOrEqualTo(firstDelay);

        // The broker returns and the retried event is delivered exactly like the first attempt would
        // have been: same stored payload, same event id.
        kafka.start();
        makeDue(eventId);
        relayAgainstTheBroker(properties).relayPending();

        assertThat(outboxEventRepository.findAll()).isEmpty();
        List<ConsumerRecord<String, String>> records = consumeMatching(
                record -> ("ACCOUNT-" + accountId).equals(record.key()), 1, Duration.ofSeconds(30));
        assertThat(records).hasSize(1);
        BusinessEvent<AccountStatusChangedPayload> delivered =
                json.fromJson(records.get(0).value(), AccountStatusChangedPayload.class);
        assertThat(delivered.eventId()).isEqualTo(eventId);
        assertThat(delivered.payload().newStatus()).isEqualTo(AccountStatus.CLOSED);
    }
}
