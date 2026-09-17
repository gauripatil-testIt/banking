package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.*;
import com.techverito.banking.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    TransactionRepository transactionRepository;

    @InjectMocks
    TransactionService transactionService;

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

    private Transaction transaction(Long id, Account account, TransactionType type, TransactionStatus status) {
        return Transaction.builder()
                .id(id).account(account).type(type)
                .amount(BigDecimal.valueOf(100))
                .balanceAfter(BigDecimal.valueOf(900))
                .status(status)
                .build();
    }

    @Test
    void list_noFilters_returnsAllMapped() {
        Customer c = customer(1L);
        Account a = account(1L, c);
        Transaction t1 = transaction(1L, a, TransactionType.DEPOSIT, TransactionStatus.COMPLETED);
        Transaction t2 = transaction(2L, a, TransactionType.WITHDRAWAL, TransactionStatus.PENDING);
        when(transactionRepository.findByFilters(null, null, null)).thenReturn(List.of(t1, t2));

        List<TransactionResponse> res = transactionService.list(null, null, null);

        assertThat(res).hasSize(2);
        assertThat(res.get(0).id()).isEqualTo(1L);
        assertThat(res.get(1).id()).isEqualTo(2L);
    }

    @Test
    void list_withStatusFilter_returnsFiltered() {
        Customer c = customer(1L);
        Account a = account(1L, c);
        Transaction t1 = transaction(1L, a, TransactionType.DEPOSIT, TransactionStatus.COMPLETED);
        when(transactionRepository.findByFilters(TransactionStatus.COMPLETED, null, null)).thenReturn(List.of(t1));

        List<TransactionResponse> res = transactionService.list(TransactionStatus.COMPLETED, null, null);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).status()).isEqualTo(TransactionStatus.COMPLETED);
    }

    @Test
    void list_withTypeFilter_returnsFiltered() {
        Customer c = customer(1L);
        Account a = account(1L, c);
        Transaction t1 = transaction(1L, a, TransactionType.DEPOSIT, TransactionStatus.COMPLETED);
        when(transactionRepository.findByFilters(null, TransactionType.DEPOSIT, null)).thenReturn(List.of(t1));

        List<TransactionResponse> res = transactionService.list(null, TransactionType.DEPOSIT, null);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).type()).isEqualTo(TransactionType.DEPOSIT);
    }

    @Test
    void list_withCustomerIdFilter_returnsFiltered() {
        Customer c = customer(1L);
        Account a = account(1L, c);
        Transaction t1 = transaction(1L, a, TransactionType.DEPOSIT, TransactionStatus.COMPLETED);
        when(transactionRepository.findByFilters(null, null, 1L)).thenReturn(List.of(t1));

        List<TransactionResponse> res = transactionService.list(null, null, 1L);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).accountId()).isEqualTo(1L);
    }

    @Test
    void list_withCombinedFilters_appliesAllAsAnd() {
        Customer c = customer(1L);
        Account a = account(1L, c);
        Transaction t1 = transaction(1L, a, TransactionType.DEPOSIT, TransactionStatus.COMPLETED);
        when(transactionRepository.findByFilters(TransactionStatus.COMPLETED, TransactionType.DEPOSIT, 1L))
                .thenReturn(List.of(t1));

        List<TransactionResponse> res = transactionService.list(TransactionStatus.COMPLETED, TransactionType.DEPOSIT, 1L);

        assertThat(res).hasSize(1);
        verify(transactionRepository).findByFilters(TransactionStatus.COMPLETED, TransactionType.DEPOSIT, 1L);
    }

    @Test
    void list_noMatches_returnsEmptyList() {
        when(transactionRepository.findByFilters(TransactionStatus.FAILED, null, null)).thenReturn(List.of());

        List<TransactionResponse> res = transactionService.list(TransactionStatus.FAILED, null, null);

        assertThat(res).isEmpty();
    }
}
