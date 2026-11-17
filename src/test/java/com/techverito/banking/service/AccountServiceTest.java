package com.techverito.banking.service;

import com.techverito.banking.config.EventBusProperties;
import com.techverito.banking.config.EventBusType;
import com.techverito.banking.dto.AccountRequest;
import com.techverito.banking.dto.AccountResponse;
import com.techverito.banking.entity.*;
import com.techverito.banking.event.AccountStatusChangedPayload;
import com.techverito.banking.event.BusinessEventFactory;
import com.techverito.banking.event.BusinessEventJson;
import com.techverito.banking.event.BusinessEventOutbox;
import com.techverito.banking.event.SubjectType;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.CustomerRepository;
import com.techverito.banking.repository.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    @Mock
    AccountRepository accountRepository;

    @Mock
    CustomerRepository customerRepository;

    @Mock
    BusinessEventOutbox businessEventOutbox;

    @InjectMocks
    AccountService accountService;

    /** The real outbox writer, so the rows it would store can be inspected. */
    private BusinessEventOutbox outbox(OutboxEventRepository outboxRepository) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new BusinessEventOutbox(outboxRepository, new BusinessEventFactory(clock), new BusinessEventJson(),
                new EventBusProperties(EventBusType.IN_MEMORY, "banking.events"), clock);
    }

    private Customer customer(Long id) {
        return Customer.builder()
                .id(id).firstName("John").lastName("Doe")
                .email("john@example.com").phone("123")
                .status(CustomerStatus.ACTIVE).build();
    }

    private Account account(Long id, Customer customer) {
        return Account.builder()
                .id(id).customer(customer)
                .accountNumber("ACC001").type(AccountType.SAVINGS)
                .balance(BigDecimal.valueOf(1000)).status(AccountStatus.ACTIVE)
                .build();
    }

    private AccountRequest request() {
        return new AccountRequest(1L, "ACC001", AccountType.SAVINGS, BigDecimal.valueOf(1000), AccountStatus.ACTIVE);
    }

    @Test
    void create_validCustomer_savesAndReturnsResponse() {
        Customer c = customer(1L);
        Account a = account(1L, c);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(c));
        when(accountRepository.save(any())).thenReturn(a);

        AccountResponse res = accountService.create(request());

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.customerId()).isEqualTo(1L);
        assertThat(res.type()).isEqualTo(AccountType.SAVINGS);
        assertThat(res.balance()).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void create_customerNotFound_throwsException() {
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.create(request()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer");
    }

    @Test
    void getById_found_returnsResponse() {
        Account a = account(1L, customer(1L));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(a));

        AccountResponse res = accountService.getById(1L);

        assertThat(res.accountNumber()).isEqualTo("ACC001");
        assertThat(res.status()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void getById_notFound_throwsException() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void list_noFilter_returnsAll() {
        Customer c = customer(1L);
        when(accountRepository.findAll()).thenReturn(List.of(account(1L, c), account(2L, c)));

        List<AccountResponse> res = accountService.list(null);

        assertThat(res).hasSize(2);
    }

    @Test
    void list_withCustomerId_returnsFiltered() {
        Customer c = customer(1L);
        when(accountRepository.findByCustomer_Id(1L)).thenReturn(List.of(account(1L, c)));

        List<AccountResponse> res = accountService.list(1L);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).customerId()).isEqualTo(1L);
    }

    @Test
    void update_found_updatesAndReturns() {
        Customer c = customer(1L);
        Account existing = account(1L, c);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customerRepository.findById(1L)).thenReturn(Optional.of(c));
        when(accountRepository.save(any())).thenReturn(existing);

        AccountResponse res = accountService.update(1L, request());

        assertThat(res).isNotNull();
        verify(accountRepository).save(existing);
    }

    @Test
    void update_accountNotFound_throwsException() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.update(99L, request()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Account");
    }

    @Test
    void delete_found_deletesById() {
        Account a = account(1L, customer(1L));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(a));

        accountService.delete(1L);

        verify(accountRepository).deleteById(1L);
    }

    @Test
    void delete_notFound_throwsException() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createIsRecordedAsAccountOpened() {
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        when(outboxRepository.nextSequenceNo("ACCOUNT-1")).thenReturn(1L);
        Customer c = customer(1L);
        AccountRepository repository = mock(AccountRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        when(customers.findById(1L)).thenReturn(Optional.of(c));
        when(repository.save(any())).thenReturn(account(1L, c));
        AccountService service = new AccountService(repository, customers, outbox(outboxRepository));

        service.create(request());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent row = captor.getValue();
        assertThat(row.getEventType()).isEqualTo("account.opened");
        assertThat(row.getEventVersion()).isEqualTo(1);
        assertThat(row.getSubjectType()).isEqualTo(SubjectType.ACCOUNT);
        assertThat(row.getSubjectId()).isEqualTo(1L);
        assertThat(row.getOrderingKey()).isEqualTo("ACCOUNT-1");
        assertThat(row.getTopic()).isEqualTo("banking.events");
        assertThat(row.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        assertThat(row.getPayload())
                .contains("\"type\":\"account.opened\"")
                .contains("\"accountId\":1")
                .contains("\"customerId\":1");
    }

    /**
     * Two status changes of one account are recorded under the same ordering key with increasing
     * sequence numbers, so the relay can never send the newer change before the older one.
     */
    @Test
    void statusChangeIsRecordedWithSequence() {
        OutboxEventRepository outboxRepository = mock(OutboxEventRepository.class);
        when(outboxRepository.nextSequenceNo("ACCOUNT-1")).thenReturn(1L, 2L);
        Customer c = customer(1L);
        Account existing = account(1L, c);
        AccountRepository repository = mock(AccountRepository.class);
        CustomerRepository customers = mock(CustomerRepository.class);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(customers.findById(1L)).thenReturn(Optional.of(c));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AccountService service = new AccountService(repository, customers, outbox(outboxRepository));

        service.update(1L, new AccountRequest(1L, "ACC001", AccountType.SAVINGS, BigDecimal.valueOf(1000),
                AccountStatus.CLOSED));
        service.update(1L, new AccountRequest(1L, "ACC001", AccountType.SAVINGS, BigDecimal.valueOf(1000),
                AccountStatus.INACTIVE));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(2)).save(captor.capture());
        List<OutboxEvent> rows = captor.getAllValues();

        assertThat(rows).extracting(OutboxEvent::getEventType).containsOnly("account.status.changed");
        assertThat(rows).extracting(OutboxEvent::getOrderingKey).containsOnly("ACCOUNT-1");
        assertThat(rows).extracting(OutboxEvent::getSequenceNo).containsExactly(1L, 2L);

        BusinessEventJson json = new BusinessEventJson();
        AccountStatusChangedPayload first = json.fromJson(rows.get(0).getPayload(),
                AccountStatusChangedPayload.class).payload();
        AccountStatusChangedPayload second = json.fromJson(rows.get(1).getPayload(),
                AccountStatusChangedPayload.class).payload();
        assertThat(first.accountId()).isEqualTo(1L);
        assertThat(first.previousStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(first.newStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(second.previousStatus()).isEqualTo(AccountStatus.CLOSED);
        assertThat(second.newStatus()).isEqualTo(AccountStatus.INACTIVE);
    }
}
