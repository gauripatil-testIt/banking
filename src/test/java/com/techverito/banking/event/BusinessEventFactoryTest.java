package com.techverito.banking.event;

import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.entity.ExportType;
import com.techverito.banking.exception.BusinessEventValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The factory is the only place an envelope is stamped, so these tests pin the parts
 * it must supply: a unique id, the clock's time, and the type/version pair.
 */
class BusinessEventFactoryTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final String CAUSED_BY = "ops.user";

    private static final String CORRELATION_ID = "corr-2025-03-04-0001";

    private BusinessEventFactory factory;

    @BeforeEach
    void setUp() {
        factory = new BusinessEventFactory(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static EventPayload payloadFor(BusinessEventType type) {
        return switch (type) {
            case CUSTOMER_REGISTERED -> new CustomerRegisteredPayload(1L, "John", "Doe", "john@example.com");
            case ACCOUNT_OPENED -> new AccountOpenedPayload(10L, 1L, "00012345", AccountType.SAVINGS,
                    new BigDecimal("1000.00"));
            case ACCOUNT_STATUS_CHANGED -> new AccountStatusChangedPayload(10L, "00012345", AccountStatus.ACTIVE,
                    AccountStatus.CLOSED);
            case DOCUMENT_UPLOADED -> new DocumentUploadedPayload(7L, 1L, DocumentType.ID_PROOF,
                    "sample-valid.pdf", 650L, "ops.user");
            case EXPORT_REQUESTED -> new ExportRequestedPayload(ExportType.CUSTOMERS, "ops.user",
                    "banking-export-2025-03-04-0907.csv");
            case STATEMENT_GENERATED -> new StatementGeneratedPayload(3L, 10L, LocalDate.parse("2025-02-01"),
                    LocalDate.parse("2025-02-28"), "pdf");
        };
    }

    @Test
    void stampsUniqueIdAndClockTime() {
        BusinessEvent<CustomerRegisteredPayload> first = factory.create(BusinessEventType.CUSTOMER_REGISTERED,
                SubjectType.CUSTOMER, 1L, CAUSED_BY, CORRELATION_ID,
                new CustomerRegisteredPayload(1L, "John", "Doe", "john@example.com"));
        BusinessEvent<CustomerRegisteredPayload> second = factory.create(BusinessEventType.CUSTOMER_REGISTERED,
                SubjectType.CUSTOMER, 1L, CAUSED_BY, CORRELATION_ID,
                new CustomerRegisteredPayload(1L, "John", "Doe", "john@example.com"));

        assertThat(first.eventId()).isNotNull();
        assertThat(second.eventId()).isNotNull();
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
        assertThat(first.occurredAt()).isEqualTo(NOW);
        assertThat(second.occurredAt()).isEqualTo(NOW);

        // Everything the announcer supplied is passed through unchanged.
        assertThat(first.subjectType()).isEqualTo(SubjectType.CUSTOMER);
        assertThat(first.subjectId()).isEqualTo(1L);
        assertThat(first.causedBy()).isEqualTo(CAUSED_BY);
        assertThat(first.correlationId()).isEqualTo(CORRELATION_ID);
        assertThat(first.payload().email()).isEqualTo("john@example.com");
    }

    @Test
    void usesTheEventTypesWireValueAndCurrentVersion() {
        for (BusinessEventType type : BusinessEventType.values()) {
            BusinessEvent<EventPayload> event = factory.create(type, SubjectType.CUSTOMER, 1L, CAUSED_BY,
                    CORRELATION_ID, payloadFor(type));

            assertThat(event.type()).isEqualTo(type.getWireValue());
            assertThat(event.version()).isEqualTo(type.getCurrentVersion());
            assertThat(event.version()).isEqualTo(1);
        }
    }

    @Test
    void requiresCorrelationId() {
        assertThatThrownBy(() -> factory.create(BusinessEventType.CUSTOMER_REGISTERED, SubjectType.CUSTOMER, 1L,
                CAUSED_BY, "   ", new CustomerRegisteredPayload(1L, "John", "Doe", "john@example.com")))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("correlation id");

        assertThatThrownBy(() -> factory.create(BusinessEventType.CUSTOMER_REGISTERED, SubjectType.CUSTOMER, 1L,
                CAUSED_BY, null, new CustomerRegisteredPayload(1L, "John", "Doe", "john@example.com")))
                .isInstanceOf(BusinessEventValidationException.class)
                .hasMessageContaining("correlation id");
    }
}
