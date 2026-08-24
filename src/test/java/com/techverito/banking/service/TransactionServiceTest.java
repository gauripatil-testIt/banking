package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.*;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    TransactionRepository transactionRepository;

    @Mock
    AccountRepository accountRepository;

    @InjectMocks
    TransactionService transactionService;

    private Customer customer(Long id) {
        return Customer.builder()
                .id(id).firstName("John").lastName("Doe")
                .email("john@example.com").phone("123")
                .status(CustomerStatus.ACTIVE).build();
    }

    private Account account(Long id) {
        return Account.builder()
                .id(id).customer(customer(1L))
                .accountNumber("ACC001").type(AccountType.SAVINGS)
                .balance(BigDecimal.valueOf(1000)).status(AccountStatus.ACTIVE)
                .currency("USD")
                .build();
    }

    private Transaction transaction(Long id, Account account) {
        return Transaction.builder()
                .id(id).account(account)
                .type(TransactionType.DEPOSIT)
                .amount(BigDecimal.valueOf(100))
                .balanceAfter(BigDecimal.valueOf(1100))
                .status(TransactionStatus.PENDING)
                .targetAccountNumber(null)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private TransactionRequest request() {
        return new TransactionRequest(1L, TransactionType.DEPOSIT, BigDecimal.valueOf(100), BigDecimal.valueOf(1100), TransactionStatus.PENDING, null);
    }

    @Test
    void create_validAccount_savesAndReturnsResponse() {
        Account a = account(1L);
        Transaction t = transaction(1L, a);
        when(accountRepository.findById(1L)).thenReturn(Optional.of(a));
        when(transactionRepository.save(any())).thenReturn(t);

        TransactionResponse res = transactionService.create(request());

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.accountId()).isEqualTo(1L);
        assertThat(res.type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(res.amount()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(res.balanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(1100));
        assertThat(res.status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void create_accountNotFound_throwsException() {
        when(accountRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.create(request()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Account");
    }

    @Test
    void getById_found_returnsResponse() {
        Account a = account(1L);
        Transaction t = transaction(1L, a);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(t));

        TransactionResponse res = transactionService.getById(1L);

        assertThat(res.accountId()).isEqualTo(1L);
        assertThat(res.status()).isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void getById_notFound_throwsException() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Transaction");
    }

    @Test
    void list_noFilter_returnsAll() {
        Account a = account(1L);
        when(transactionRepository.findAll()).thenReturn(List.of(transaction(1L, a), transaction(2L, a)));

        List<TransactionResponse> res = transactionService.list(null);

        assertThat(res).hasSize(2);
    }

    @Test
    void list_withAccountId_returnsFiltered() {
        Account a = account(1L);
        when(transactionRepository.findByAccount_Id(1L)).thenReturn(List.of(transaction(1L, a)));

        List<TransactionResponse> res = transactionService.list(1L);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).accountId()).isEqualTo(1L);
    }

    @Test
    void update_found_updatesAndReturns() {
        Account a = account(1L);
        Transaction existing = transaction(1L, a);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(transactionRepository.save(any())).thenReturn(existing);

        TransactionRequest updateReq = new TransactionRequest(1L, TransactionType.DEPOSIT, BigDecimal.valueOf(100), BigDecimal.valueOf(1100), TransactionStatus.COMPLETED, null);

        TransactionResponse res = transactionService.update(1L, updateReq);

        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo(TransactionStatus.COMPLETED);
        verify(transactionRepository).save(existing);
    }

    @Test
    void update_notFound_throwsException() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.update(99L, request()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Transaction");
    }

    @Test
    void delete_found_deletesById() {
        Account a = account(1L);
        Transaction t = transaction(1L, a);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(t));

        transactionService.delete(1L);

        verify(transactionRepository).deleteById(1L);
    }

    @Test
    void delete_notFound_throwsException() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
