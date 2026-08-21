package com.techverito.banking.service;

import com.techverito.banking.dto.AccountRequest;
import com.techverito.banking.dto.AccountResponse;
import com.techverito.banking.entity.*;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    AccountRepository accountRepository;

    @Mock
    CustomerRepository customerRepository;

    @InjectMocks
    AccountService accountService;

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
                .currency(Currency.getInstance("USD"))
                .build();
    }

    private AccountRequest request() {
        return new AccountRequest(1L, "ACC001", AccountType.SAVINGS, BigDecimal.valueOf(1000), AccountStatus.ACTIVE, null);
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
        assertThat(res.currency()).isEqualTo(Currency.getInstance("USD"));
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
        assertThat(res.currency()).isEqualTo(Currency.getInstance("USD"));
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
        assertThat(res.currency()).isEqualTo(Currency.getInstance("USD"));
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
}
