package com.techverito.banking.event.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.dockerjava.api.DockerClient;
import com.techverito.banking.BankingApplication;
import com.techverito.banking.dto.AccountRequest;
import com.techverito.banking.dto.AccountResponse;
import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.dto.CustomerResponse;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.entity.BusinessEventOutboxEntry;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.OutboxEventStatus;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.EventType;
import com.techverito.banking.repository.BusinessEventOutboxRepository;
import com.techverito.banking.service.AccountService;
import com.techverito.banking.service.CustomerService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof of BANK-7 against a real broker started by Testcontainers: the real
 * business services, the real outbox, the real scheduled drain and the real Kafka client
 * – there is no stubbed publishing path anywhere in this test.
 *
 * <p>What it shows: an event recorded by a business action reaches
 * {@code banking.business-events} with the envelope and key the convention requires; two
 * actions on the same account arrive in the order they happened; and an action performed
 * while the broker is stopped still succeeds, with its event published once the broker is
 * back. It needs Docker and nothing else – no external broker, no credentials and no
 * network beyond the container – and is skipped when Docker is unavailable, exactly as
 * {@link com.techverito.banking.event.kafka.KafkaEventBusContainerTest} is.</p>
 *
 * <p>The broker is stopped by killing its container and started again by starting that
 * same container, so the bootstrap address the application was given at startup stays
 * valid: a re-created container would come back on a different host port and the
 * application could never find it again.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class OutboxEndToEndKafkaContainerTest {

    private static final String TOPIC = "banking.business-events";

    /** How long an event may take to reach the topic, or a row to become PUBLISHED. */
    private static final long TIMEOUT_SECONDS = 180L;

    /** How long the broker may take to stop listening, or to listen again. */
    private static final long BROKER_TIMEOUT_SECONDS = 120L;

    @Container
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    private static ConfigurableApplicationContext context;

    private static CustomerService customerService;

    private static AccountService accountService;

    private static BusinessEventOutboxRepository outboxRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeAll
    static void startApplicationAgainstTheContainer() {
        context = new SpringApplicationBuilder(BankingApplication.class)
                .web(WebApplicationType.NONE)
                .properties(
                        // The real transport, pointed at the container this test started.
                        "banking.events.transport=kafka",
                        "banking.events.topic=" + TOPIC,
                        "banking.events.consumer-group=outbox-e2e-subscribers",
                        "spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                        // Retry often and generously, so a short outage never dead-letters an
                        // event this test expects to see delivered once the broker is back.
                        "banking.events.outbox.max-attempts=50",
                        "banking.events.outbox.retry-delay=PT1S",
                        "banking.events.outbox.drain-interval=PT0.5S",
                        // Documents are not part of this test; the local backend needs no AWS.
                        "banking.documents.storage=local",
                        "banking.documents.storage-dir=./target/test-documents/outbox-e2e",
                        // An in-memory database of its own, generated from the entity mappings.
                        "spring.datasource.url=jdbc:h2:mem:outbox-e2e;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "spring.jpa.hibernate.ddl-auto=create-drop",
                        "spring.main.banner-mode=off")
                .run();

        customerService = context.getBean(CustomerService.class);
        accountService = context.getBean(AccountService.class);
        outboxRepository = context.getBean(BusinessEventOutboxRepository.class);
    }

    @AfterAll
    static void stopApplication() {
        if (context != null) {
            context.close();
        }
    }

    private static String customerKey(Long customerId) {
        return "customer:" + customerId;
    }

    private static String accountKey(Long accountId) {
        return "account:" + accountId;
    }

    private static String shortId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static CustomerRequest customerRequest(String purpose) {
        return new CustomerRequest("Bank", "Seven", purpose + "-" + UUID.randomUUID() + "@example.com",
                "1234567890", CustomerStatus.ACTIVE);
    }

    private static AccountRequest accountRequest(Long customerId, String accountNumber) {
        return accountRequest(customerId, accountNumber, AccountStatus.ACTIVE);
    }

    private static AccountRequest accountRequest(Long customerId, String accountNumber, AccountStatus status) {
        return new AccountRequest(customerId, accountNumber, AccountType.SAVINGS, new BigDecimal("1000.00"), status);
    }

    /** The stored outbox rows of one business key, oldest first. */
    private static List<BusinessEventOutboxEntry> rowsOf(String businessKey) {
        return outboxRepository.findByBusinessKeyOrderByIdAsc(businessKey);
    }

    /** Waits for the first row recorded for a business key, so a slow commit cannot race the test. */
    private static BusinessEventOutboxEntry awaitRowOf(String businessKey) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            List<BusinessEventOutboxEntry> rows = rowsOf(businessKey);
            if (!rows.isEmpty()) {
                return rows.get(0);
            }
            Thread.sleep(200);
        }
        throw new AssertionError("No outbox row was recorded for " + businessKey);
    }

    /**
     * Waits until the drain marked the stored event {@code PUBLISHED}, which under the
     * real adapter means the broker acknowledged it.
     */
    private static BusinessEventOutboxEntry awaitPublished(String businessKey, String eventId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        BusinessEventOutboxEntry row = null;
        while (System.nanoTime() < deadline) {
            row = rowsOf(businessKey).stream()
                    .filter(candidate -> candidate.getEventId().equals(eventId))
                    .findFirst()
                    .orElse(null);
            if (row != null && row.getStatus() == OutboxEventStatus.PUBLISHED) {
                return row;
            }
            Thread.sleep(500);
        }
        assertThat(row).as("the outbox row of %s", eventId).isNotNull();
        assertThat(row.getStatus())
                .as("the outbox row of %s once the broker is back; attempts=%s, lastError=%s",
                        eventId, row.getAttempts(), row.getLastError())
                .isEqualTo(OutboxEventStatus.PUBLISHED);
        return row;
    }

    private Properties consumerConfiguration() {
        Properties config = new Properties();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000);
        config.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000);
        return config;
    }

    /**
     * Reads the topic from the beginning on every partition with a real consumer and
     * returns the records of the expected events in the order they were read.
     *
     * <p>Partitions are assigned directly instead of joining a consumer group, so the test
     * neither waits for a rebalance nor disturbs the application's own subscriber group.
     * The events of one business key share a partition, so the order they are read in is
     * the order they were appended in.</p>
     */
    private List<ConsumerRecord<String, String>> awaitEventsOnTopic(String... eventIds) {
        Set<String> stillMissing = new LinkedHashSet<>(Arrays.asList(eventIds));
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerConfiguration())) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(partition -> new TopicPartition(TOPIC, partition.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
            while (!stillMissing.isEmpty() && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (stillMissing.remove(eventIdOf(record.value()))) {
                        received.add(record);
                    }
                }
            }
        }
        assertThat(stillMissing).as("events that never reached %s", TOPIC).isEmpty();
        return received;
    }

    private String eventIdOf(String payload) {
        try {
            return objectMapper.readTree(payload).path("eventId").asText();
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("A record on " + TOPIC + " is not a JSON business event", ex);
        }
    }

    private BusinessEvent envelopeOf(ConsumerRecord<String, String> record) {
        try {
            return objectMapper.readValue(record.value(), BusinessEvent.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("A record on " + TOPIC + " is not a business event", ex);
        }
    }

    private ConsumerRecord<String, String> recordOf(List<ConsumerRecord<String, String>> received, String eventId) {
        return received.stream()
                .filter(record -> eventIdOf(record.value()).equals(eventId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The event " + eventId + " was not read from " + TOPIC));
    }

    private static DockerClient dockerClient() {
        return DockerClientFactory.instance().client();
    }

    /**
     * Stops the broker by killing its container, and waits until nothing listens on the
     * advertised address any more, so a business action taken next really is taken while
     * Kafka is down.
     */
    private static void stopBroker() throws InterruptedException {
        dockerClient().killContainerCmd(KAFKA.getContainerId()).exec();
        awaitBrokerReachable(false);
    }

    /**
     * Starts the broker container again. It is a no-op when the broker is already
     * listening, so it is safe to call from a {@code finally} block as well.
     */
    private static void startBroker() throws InterruptedException {
        if (!isBrokerReachable()) {
            dockerClient().startContainerCmd(KAFKA.getContainerId()).exec();
        }
        awaitBrokerReachable(true);
    }

    private static void awaitBrokerReachable(boolean reachable) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(BROKER_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (isBrokerReachable() == reachable) {
                return;
            }
            Thread.sleep(200);
        }
        assertThat(isBrokerReachable())
                .as("the broker advertised as %s should be %s", KAFKA.getBootstrapServers(),
                        reachable ? "reachable" : "unreachable")
                .isEqualTo(reachable);
    }

    /** Whether the broker's advertised address accepts a TCP connection. */
    private static boolean isBrokerReachable() {
        String bootstrapServers = KAFKA.getBootstrapServers();
        int hostStart = bootstrapServers.contains("://") ? bootstrapServers.indexOf("://") + 3 : 0;
        int portSeparator = bootstrapServers.lastIndexOf(':');
        String host = bootstrapServers.substring(hostStart, portSeparator);
        int port = Integer.parseInt(bootstrapServers.substring(portSeparator + 1));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    @Test
    void customerRegistrationAndAccountOpening_reachTheTopicKeyedByWhatTheyConcern() {
        CustomerResponse customer = customerService.create(customerRequest("registered"));
        AccountResponse account = accountService.create(accountRequest(customer.id(), "ACC-OPEN-" + shortId()));

        BusinessEventOutboxEntry customerRow = rowsOf(customerKey(customer.id())).get(0);
        BusinessEventOutboxEntry accountRow = rowsOf(accountKey(account.id())).get(0);
        assertThat(customerRow.getEventType()).isEqualTo(EventType.CUSTOMER_REGISTERED);
        assertThat(customerRow.getBusinessKey()).isEqualTo(customerKey(customer.id()));
        assertThat(accountRow.getEventType()).isEqualTo(EventType.ACCOUNT_OPENED);
        assertThat(accountRow.getBusinessKey()).isEqualTo(accountKey(account.id()));

        List<ConsumerRecord<String, String>> received =
                awaitEventsOnTopic(customerRow.getEventId(), accountRow.getEventId());

        ConsumerRecord<String, String> customerRecord = recordOf(received, customerRow.getEventId());
        assertThat(customerRecord.key()).isEqualTo(customerKey(customer.id()));
        BusinessEvent registered = envelopeOf(customerRecord);
        assertThat(registered.eventId()).isEqualTo(customerRow.getEventId());
        assertThat(registered.type()).isEqualTo(EventType.CUSTOMER_REGISTERED);
        assertThat(registered.version()).isEqualTo(BusinessEvent.CURRENT_VERSION);
        assertThat(registered.occurredAt()).isNotNull();
        assertThat(registered.customerId()).isEqualTo(customer.id());
        assertThat(registered.accountId()).isNull();
        assertThat(registered.actor()).isEqualTo(customerRow.getActor());
        assertThat(registered.correlationId()).isEqualTo(customerRow.getCorrelationId());

        ConsumerRecord<String, String> accountRecord = recordOf(received, accountRow.getEventId());
        assertThat(accountRecord.key()).isEqualTo(accountKey(account.id()));
        BusinessEvent opened = envelopeOf(accountRecord);
        assertThat(opened.eventId()).isEqualTo(accountRow.getEventId());
        assertThat(opened.type()).isEqualTo(EventType.ACCOUNT_OPENED);
        assertThat(opened.version()).isEqualTo(BusinessEvent.CURRENT_VERSION);
        assertThat(opened.occurredAt()).isNotNull();
        assertThat(opened.customerId()).isEqualTo(customer.id());
        assertThat(opened.accountId()).isEqualTo(account.id());
        assertThat(opened.actor()).isEqualTo(accountRow.getActor());
        assertThat(opened.correlationId()).isEqualTo(accountRow.getCorrelationId());
    }

    @Test
    void twoActionsOnTheSameAccount_arriveOnTheTopicInTheOrderTheyHappened() {
        CustomerResponse customer = customerService.create(customerRequest("ordered"));
        AccountResponse account = accountService.create(accountRequest(customer.id(), "ACC-ORDER-" + shortId()));
        accountService.update(account.id(),
                accountRequest(customer.id(), account.accountNumber(), AccountStatus.INACTIVE));

        List<BusinessEventOutboxEntry> rows = rowsOf(accountKey(account.id()));
        assertThat(rows).extracting(BusinessEventOutboxEntry::getEventType)
                .containsExactly(EventType.ACCOUNT_OPENED, EventType.ACCOUNT_STATUS_CHANGED);

        List<ConsumerRecord<String, String>> received =
                awaitEventsOnTopic(rows.get(0).getEventId(), rows.get(1).getEventId());

        // Same key, so same partition, so the read order is the append order.
        assertThat(received.stream().map(record -> eventIdOf(record.value())).toList())
                .containsExactly(rows.get(0).getEventId(), rows.get(1).getEventId());
        assertThat(received.stream().map(ConsumerRecord::key).toList())
                .containsExactly(accountKey(account.id()), accountKey(account.id()));
        assertThat(received.stream().map(record -> envelopeOf(record).type()).toList())
                .containsExactly(EventType.ACCOUNT_OPENED, EventType.ACCOUNT_STATUS_CHANGED);
    }

    @Test
    void aBusinessActionSucceedsWhileTheBrokerIsDownAndItsEventArrivesWhenItReturns() throws Exception {
        stopBroker();
        try {
            // Criterion 5: the customer action is committed without waiting for Kafka.
            CustomerResponse customer = customerService.create(customerRequest("outage"));
            BusinessEventOutboxEntry pending = awaitRowOf(customerKey(customer.id()));

            assertThat(pending.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
            assertThat(pending.getEventType()).isEqualTo(EventType.CUSTOMER_REGISTERED);
            assertThat(pending.getCustomerId()).isEqualTo(customer.id());
            assertThat(pending.getAccountId()).isNull();
            assertThat(pending.getPublishedAt()).isNull();
            assertThat(isBrokerReachable()).as("the broker is stopped while the action is served").isFalse();

            startBroker();

            BusinessEventOutboxEntry published = awaitPublished(customerKey(customer.id()), pending.getEventId());
            assertThat(published.getPublishedAt()).isAfterOrEqualTo(pending.getCreatedAt());

            List<ConsumerRecord<String, String>> received = awaitEventsOnTopic(pending.getEventId());
            assertThat(received).hasSize(1);
            assertThat(received.get(0).key()).isEqualTo(customerKey(customer.id()));
            BusinessEvent recovered = envelopeOf(received.get(0));
            assertThat(recovered.eventId()).isEqualTo(pending.getEventId());
            assertThat(recovered.type()).isEqualTo(EventType.CUSTOMER_REGISTERED);
            assertThat(recovered.customerId()).isEqualTo(customer.id());
        } finally {
            // Whatever happened above, the broker is left running for the rest of the suite.
            startBroker();
        }
    }
}
