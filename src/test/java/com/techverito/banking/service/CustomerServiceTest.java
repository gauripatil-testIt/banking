package com.techverito.banking.service;

import com.techverito.banking.config.EventBusProperties;
import com.techverito.banking.config.EventBusType;
import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.dto.CustomerResponse;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.OutboxEvent;
import com.techverito.banking.entity.OutboxEventStatus;
import com.techverito.banking.event.BusinessEventFactory;
import com.techverito.banking.event.BusinessEventJson;
import com.techverito.banking.event.BusinessEventOutbox;
import com.techverito.banking.event.SubjectType;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.CustomerRepository;
import com.techverito.banking.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    @Mock
    CustomerRepository customerRepository;

    @Mock
    BusinessEventOutbox businessEventOutbox;

    @InjectMocks
    CustomerService customerService;

    private CustomerRequest request() {
        return new CustomerRequest("John", "Doe", "john@example.com", "1234567890", CustomerStatus.ACTIVE);
    }

    private Customer customer(Long id) {
        return Customer.builder()
                .id(id).firstName("John").lastName("Doe")
                .email("john@example.com").phone("1234567890")
                .status(CustomerStatus.ACTIVE).build();
    }

    /** The saved customer the repository returns: same fields, with the generated id. */
    private Customer withId(Customer saved, Long id) {
        return Customer.builder()
                .id(id).firstName(saved.getFirstName()).lastName(saved.getLastName())
                .email(saved.getEmail()).phone(saved.getPhone())
                .status(saved.getStatus()).createdAt(saved.getCreatedAt())
                .build();
    }

    /** The real outbox writer, so the row it would store can be inspected. */
    private BusinessEventOutbox outbox(OutboxEventRepository outboxRepository) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new BusinessEventOutbox(outboxRepository, new BusinessEventFactory(clock), new BusinessEventJson(),
                new EventBusProperties(EventBusType.IN_MEMORY, "banking.events"), clock);
    }

    @Test
    void create_savesAndReturnsResponse() {
        Customer saved = customer(1L);
        when(customerRepository.save(any())).thenReturn(saved);

        CustomerResponse res = customerService.create(request());

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.firstName()).isEqualTo("John");
        assertThat(res.status()).isEqualTo(CustomerStatus.ACTIVE);
        verify(customerRepository).save(any(Customer.class));
    }

    @Test
    void create_setsCreatedDateOnPersistedCustomer() {
        when(customerRepository.save(any())).thenAnswer(invocation -> withId(invocation.getArgument(0), 1L));
        Instant before = Instant.now();

        customerService.create(request());

        Instant after = Instant.now();
        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(captor.capture());
        Customer persisted = captor.getValue();
        assertThat(persisted.getCreatedAt()).isNotNull();
        assertThat(persisted.getCreatedAt()).isBetween(before.minusSeconds(60), after.plusSeconds(60));
    }

    @Test
    void getById_found_returnsResponse() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));

        CustomerResponse res = customerService.getById(1L);

        assertThat(res.email()).isEqualTo("john@example.com");
    }

    @Test
    void getById_notFound_throwsException() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void getAll_returnsAll() {
        when(customerRepository.findAll()).thenReturn(List.of(customer(1L), customer(2L)));

        List<CustomerResponse> res = customerService.getAll();

        assertThat(res).hasSize(2);
    }

    @Test
    void update_found_updatesFields() {
        Customer existing = customer(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customerRepository.save(any())).thenReturn(existing);

        CustomerResponse res = customerService.update(1L,
                new CustomerRequest("Jane", "Smith", "jane@example.com", "999", CustomerStatus.INACTIVE));

        assertThat(res).isNotNull();
        verify(customerRepository).save(existing);
    }

    @Test
    void update_notFound_throwsException() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.update(99L, request()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_found_deletesById() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));

        customerService.delete(1L);

        verify(customerRepository).deleteById(1L);
    }

    @Test
    void delete_notFound_throwsException() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * The customer and its event are one unit of work: the row is written in the same transaction,
     * so a registration that rolls back does not leave an event behind either.
     */
    @Test
    void anOutboxRowIsWrittenInTheSameTransaction() {
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        when(outboxRepository.nextSequenceNo("CUSTOMER-1")).thenReturn(1L);
        CustomerRepository repository = mock(CustomerRepository.class);
        when(repository.save(any())).thenReturn(customer(1L));
        CustomerService service = new CustomerService(repository, outbox(outboxRepository));

        service.create(request());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent row = captor.getValue();
        assertThat(row.getEventType()).isEqualTo("customer.registered");
        assertThat(row.getEventVersion()).isEqualTo(1);
        assertThat(row.getSubjectType()).isEqualTo(SubjectType.CUSTOMER);
        assertThat(row.getSubjectId()).isEqualTo(1L);
        assertThat(row.getOrderingKey()).isEqualTo("CUSTOMER-1");
        assertThat(row.getTopic()).isEqualTo("banking.events");
        assertThat(row.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(row.getNextAttemptAt()).isEqualTo(NOW);
        assertThat(row.getPayload())
                .contains("\"type\":\"customer.registered\"")
                .contains("\"correlationId\"")
                .contains("\"customerId\":1")
                .contains("\"email\":\"john@example.com\"");

        // The same call that fails the customer insert never reaches the outbox, so nothing is left
        // to publish for a change that did not happen.
        CustomerRepository failingRepository = mock(CustomerRepository.class);
        when(failingRepository.save(any())).thenThrow(new DataIntegrityViolationException("insert failed"));
        OutboxEventRepository untouchedOutbox = mock(OutboxEventRepository.class);
        CustomerService failingService = new CustomerService(failingRepository, outbox(untouchedOutbox));

        assertThatThrownBy(() -> failingService.create(request()))
                .isInstanceOf(DataIntegrityViolationException.class);

        verifyNoInteractions(untouchedOutbox);
    }
}
