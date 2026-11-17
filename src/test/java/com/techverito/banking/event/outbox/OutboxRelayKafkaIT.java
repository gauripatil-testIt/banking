package com.techverito.banking.event.outbox;

import com.techverito.banking.config.KafkaEventProperties;
import com.techverito.banking.config.KafkaProducerConfig;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.OutboxEvent;
import com.techverito.banking.entity.OutboxEventStatus;
import com.techverito.banking.event.AccountStatusChangedPayload;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventJson;
import com.techverito.banking.event.BusinessEventType;
import com.techverito.banking.event.SubjectType;
import com.techverito.banking.repository.OutboxEventRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay against a real broker: a real Kafka container, the real producer configuration from
 * {@link KafkaProducerConfig} and the real {@link OutboxRelay}. There is no mocking of the
 * publishing path anywhere in this class - that is what the story asks for - so what is proved here
 * is what a reviewer can see in the real topic {@code banking.events}.
 *
 * <p>The tests are named {@code *IT} and need Docker, like the existing LocalStack integration
 * test: the unit suite still runs on a machine without a daemon.</p>
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class OutboxRelayKafkaIT {

    private static final String TOPIC = "banking.events";

    private static final Instant OCCURRED_AT = Instant.parse("2025-03-04T09:07:45Z");

    private static final AtomicLong NEXT_SUBJECT_ID = new AtomicLong(10_000L);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:3.8.0"));

    @Autowired
    OutboxEventRepository outboxEventRepository;

    private final BusinessEventJson json = new BusinessEventJson();

    private KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        outboxEventRepository.deleteAll();
        KafkaProducerConfig producerConfig = new KafkaProducerConfig();
        kafkaTemplate = producerConfig.kafkaTemplate(producerConfig.producerFactory(
                new KafkaEventProperties(KAFKA.getBootstrapServers(), "outbox-relay-it")));
    }

    private static long nextSubjectId() {
        return NEXT_SUBJECT_ID.incrementAndGet();
    }

    private static OutboxRelayProperties relayProperties(int sendTimeoutSeconds) {
        return new OutboxRelayProperties(50L, 50L, 500L, sendTimeoutSeconds);
    }

    private OutboxRelay relay(int sendTimeoutSeconds) {
        return new OutboxRelay(outboxEventRepository, kafkaTemplate, relayProperties(sendTimeoutSeconds),
                Clock.systemUTC());
    }

    /**
     * Runs relay passes until the outbox is empty. A pass sends the oldest pending row of each
     * ordering key, so the second event of one key is only due on a later pass.
     */
    private void relayUntilEmpty(OutboxRelay relay, int maxPasses) {
        for (int pass = 0; pass < maxPasses && !outboxEventRepository.findAll().isEmpty(); pass++) {
            relay.relayPending();
        }
    }

    /** The exact BANK-6 wire JSON of one status change, as the outbox row stores it. */
    private String statusChangedPayload(UUID eventId, long accountId, AccountStatus previous, AccountStatus next) {
        return json.toJson(new BusinessEvent<>(eventId,
                BusinessEventType.ACCOUNT_STATUS_CHANGED.getWireValue(),
                BusinessEventType.ACCOUNT_STATUS_CHANGED.getCurrentVersion(),
                OCCURRED_AT, SubjectType.ACCOUNT, accountId, "ops.user", "corr-" + eventId,
                new AccountStatusChangedPayload(accountId, "00012345", previous, next)));
    }

    private OutboxEvent storeRow(String orderingKey, long subjectId, long sequenceNo, String payload, UUID eventId) {
        return outboxEventRepository.save(OutboxEvent.builder()
                .eventId(eventId)
                .eventType(BusinessEventType.ACCOUNT_STATUS_CHANGED.getWireValue())
                .eventVersion(BusinessEventType.ACCOUNT_STATUS_CHANGED.getCurrentVersion())
                .subjectType(SubjectType.ACCOUNT)
                .subjectId(subjectId)
                .sequenceNo(sequenceNo)
                .orderingKey(orderingKey)
                .topic(TOPIC)
                .payload(payload)
                .createdAt(OCCURRED_AT)
                .nextAttemptAt(Instant.now().minusSeconds(60))
                .build());
    }

    private void makeDue(UUID eventId) {
        OutboxEvent row = outboxEventRepository.findAll().stream()
                .filter(candidate -> candidate.getEventId().equals(eventId))
                .findFirst()
                .orElseThrow();
        row.setNextAttemptAt(Instant.now().minusSeconds(1));
        outboxEventRepository.save(row);
    }

    /**
     * Reads the topic from the beginning with a fresh consumer group and returns the records that
     * match, so records left behind by another test in this class cannot influence an assertion.
     */
    private List<ConsumerRecord<String, String>> consumeMatching(Predicate<ConsumerRecord<String, String>> matches,
                                                                 int expected, Duration timeout) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-relay-it-" + UUID.randomUUID());
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

    /** The event id carried on the wire, read with the same codec a consumer would use. */
    private UUID eventIdOnWire(String wireValue) {
        return json.fromJson(wireValue, AccountStatusChangedPayload.class).eventId();
    }

    @Test
    void ordersEventsPerKeyOnTheWire() {
        long accountId = nextSubjectId();
        String orderingKey = "ACCOUNT-" + accountId;
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        String firstPayload = statusChangedPayload(firstEventId, accountId, AccountStatus.ACTIVE,
                AccountStatus.INACTIVE);
        String secondPayload = statusChangedPayload(secondEventId, accountId, AccountStatus.INACTIVE,
                AccountStatus.CLOSED);
        storeRow(orderingKey, accountId, 1L, firstPayload, firstEventId);
        storeRow(orderingKey, accountId, 2L, secondPayload, secondEventId);

        relayUntilEmpty(relay(10), 5);

        List<ConsumerRecord<String, String>> records =
                consumeMatching(record -> orderingKey.equals(record.key()), 2, Duration.ofSeconds(30));

        assertThat(records).hasSize(2);
        // Both changes of the account went to one partition under the documented ordering key, in
        // the order they happened, and the payload on the wire is the JSON stored in the row.
        assertThat(records).extracting(record -> record.value()).containsExactly(firstPayload, secondPayload);
        assertThat(records).extracting(record -> record.partition())
                .containsOnly(records.get(0).partition());
        assertThat(records).extracting(record -> eventIdOnWire(record.value()))
                .containsExactly(firstEventId, secondEventId);
        for (ConsumerRecord<String, String> record : records) {
            BusinessEvent<AccountStatusChangedPayload> event =
                    json.fromJson(record.value(), AccountStatusChangedPayload.class);
            assertThat(event.eventId()).isNotNull();
            assertThat(event.type()).isEqualTo("account.status.changed");
            assertThat(event.version()).isEqualTo(1);
        }
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }

    @Test
    void differentAccountsDoNotBlockEachOther() {
        long stuckAccount = nextSubjectId();
        long healthyAccount = nextSubjectId();
        UUID stuckEventId = UUID.randomUUID();
        UUID healthyEventId = UUID.randomUUID();
        // The leading spaces on top of the payload push the record past the producer's 1 MiB
        // max.request.size, so this subject's send fails on the client and never reaches the
        // broker, while the other subject's send must still go through in the same pass.
        String stuckPayload = statusChangedPayload(stuckEventId, stuckAccount, AccountStatus.ACTIVE,
                AccountStatus.INACTIVE) + " ".repeat(1_500_000);
        String healthyPayload = statusChangedPayload(healthyEventId, healthyAccount, AccountStatus.ACTIVE,
                AccountStatus.CLOSED);
        storeRow("ACCOUNT-" + stuckAccount, stuckAccount, 1L, stuckPayload, stuckEventId);
        storeRow("ACCOUNT-" + healthyAccount, healthyAccount, 1L, healthyPayload, healthyEventId);

        relayUntilEmpty(relay(10), 3);

        List<ConsumerRecord<String, String>> delivered =
                consumeMatching(record -> ("ACCOUNT-" + healthyAccount).equals(record.key()), 1,
                        Duration.ofSeconds(30));
        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0).value()).isEqualTo(healthyPayload);
        assertThat(outboxEventRepository.findAll())
                .extracting(row -> row.getOrderingKey())
                .doesNotContain("ACCOUNT-" + healthyAccount);

        OutboxEvent stuck = outboxEventRepository.findAll().stream()
                .filter(row -> row.getOrderingKey().equals("ACCOUNT-" + stuckAccount))
                .findFirst()
                .orElseThrow();
        assertThat(stuck.getStatus() == OutboxEventStatus.FAILED || stuck.getAttemptCount() >= 1)
                .as("the failing subject's send is recorded on its own row")
                .isTrue();
    }

    @Test
    void retryReusesTheSameEventIdSoDownstreamSeesOneEvent() {
        long accountId = nextSubjectId();
        String orderingKey = "ACCOUNT-" + accountId;
        UUID eventId = UUID.randomUUID();
        String payload = statusChangedPayload(eventId, accountId, AccountStatus.ACTIVE, AccountStatus.INACTIVE);
        storeRow(orderingKey, accountId, 1L, payload, eventId);

        // The first pass gives up on the acknowledgement (a zero-second send timeout) although the
        // broker may well have stored the record: the "acknowledgement lost" case, so the row is
        // attempted again with exactly the payload stored once.
        relay(0).relayPending();
        assertThat(outboxEventRepository.findAll())
                .extracting(row -> row.getEventId())
                .contains(eventId);

        makeDue(eventId);
        relayUntilEmpty(relay(10), 5);

        List<ConsumerRecord<String, String>> records =
                consumeMatching(record -> orderingKey.equals(record.key()), 1, Duration.ofSeconds(30));

        // However many times the record was attempted, every record on the wire carries the same
        // event id, so a consumer deduplicating on eventId sees one logical event.
        assertThat(records).isNotEmpty();
        assertThat(records).extracting(record -> eventIdOnWire(record.value())).containsOnly(eventId);
        assertThat(outboxEventRepository.findAll()).isEmpty();
    }
}
