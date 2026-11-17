package com.techverito.banking.event.outbox;

import com.techverito.banking.config.EventBusProperties;
import com.techverito.banking.config.EventBusType;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.entity.OutboxEvent;
import com.techverito.banking.event.AccountOpenedPayload;
import com.techverito.banking.event.BusinessEventFactory;
import com.techverito.banking.event.BusinessEventJson;
import com.techverito.banking.event.BusinessEventOutbox;
import com.techverito.banking.event.BusinessEventType;
import com.techverito.banking.event.CustomerRegisteredPayload;
import com.techverito.banking.event.SubjectType;
import com.techverito.banking.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The documented publishing convention, pinned: one topic ({@code banking.events}) and the
 * ordering key {@code <subjectType>-<subjectId>} with the subject type upper-cased, for example
 * {@code CUSTOMER-1} or {@code ACCOUNT-42}. The key is what lands in
 * {@code event_outbox.ordering_key} and becomes the Kafka message key, so the same string decides
 * both ordering and partitioning.
 *
 * <p>The assertions read the rows the outbox writer would store, which is the only place the
 * convention is produced, and they prove the topic is taken from configuration rather than
 * hard-coded in the writer or the relay.</p>
 */
class OrderingKeyConventionTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static BusinessEventOutbox outbox(OutboxEventRepository repository, String topic) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new BusinessEventOutbox(repository, new BusinessEventFactory(clock), new BusinessEventJson(),
                new EventBusProperties(EventBusType.KAFKA, topic), clock);
    }

    private static OutboxEventRepository repository() {
        OutboxEventRepository repository = mock(OutboxEventRepository.class);
        when(repository.nextSequenceNo(anyString())).thenReturn(1L);
        return repository;
    }

    private static List<OutboxEvent> storedRows(OutboxEventRepository repository, int expected) {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    private static void recordCustomer(BusinessEventOutbox outbox, Long customerId) {
        outbox.record(BusinessEventType.CUSTOMER_REGISTERED, SubjectType.CUSTOMER, customerId, "ops.user",
                "corr-customer-" + customerId,
                new CustomerRegisteredPayload(customerId, "John", "Doe", "john@example.com"));
    }

    @Test
    void subjectKeyIsSubjectTypeDashSubjectId() {
        OutboxEventRepository repository = repository();
        BusinessEventOutbox outbox = outbox(repository, "banking.events");

        recordCustomer(outbox, 1L);
        outbox.record(BusinessEventType.ACCOUNT_OPENED, SubjectType.ACCOUNT, 42L, "ops.user", "corr-account-42",
                new AccountOpenedPayload(42L, 1L, "00012345", AccountType.SAVINGS, new BigDecimal("1000.00")));

        List<OutboxEvent> rows = storedRows(repository, 2);
        assertThat(rows).extracting(row -> row.getOrderingKey()).containsExactly("CUSTOMER-1", "ACCOUNT-42");
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getOrderingKey())
                    .isEqualTo(row.getSubjectType().name() + "-" + row.getSubjectId());
            // The subject type is upper-cased, as the documented convention says.
            assertThat(row.getOrderingKey()).matches("[A-Z]+-[0-9]+");
        });
    }

    @Test
    void topicIsTheConfiguredBankingEventsTopic() {
        OutboxEventRepository configuredRepository = repository();
        recordCustomer(outbox(configuredRepository, "banking.events"), 7L);

        assertThat(storedRows(configuredRepository, 1))
                .extracting(row -> row.getTopic())
                .containsOnly("banking.events");

        // A differently configured topic is what the row carries, so neither the writer nor the
        // relay has a topic literal of its own.
        OutboxEventRepository overrideRepository = repository();
        recordCustomer(outbox(overrideRepository, "banking.events.custom"), 8L);

        assertThat(storedRows(overrideRepository, 1))
                .extracting(row -> row.getTopic())
                .containsOnly("banking.events.custom");
    }
}
