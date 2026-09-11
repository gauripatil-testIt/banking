package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.*;
import com.techverito.banking.exception.InvalidTransactionException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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

    private Account account(Long id, BigDecimal balance) {
        Customer c = Customer.builder()
                .id(1L).firstName("John").lastName("Doe")
                .email("john@example.com").phone("123")
                .status(CustomerStatus.ACTIVE).build();
        return Account.builder()
                .id(id).customer(c)
                .accountNumber("ACC001").type(AccountType.SAVINGS)
                .balance(balance).status(AccountStatus.ACTIVE)
                .build();
    }

    private Transaction transaction(Long id, Account account, TransactionType type,
                                     BigDecimal amount, BigDecimal balanceAfter, TransactionStatus status) {
        return Transaction.builder()
                .id(id).account(account).type(type)
                .amount(amount).balanceAfter(balanceAfter).status(status)
                .build();
    }

    private TransactionRequest request(Long accountId, TransactionType type, BigDecimal amount,
                                        BigDecimal balanceAfter, TransactionStatus status) {
        return new TransactionRequest(accountId, type, amount, balanceAfter, status);
    }

    @Test
    void create_deposit_increasesBalanceAndSaves() {
        Account acc = account(1L, BigDecimal.valueOf(1000));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(acc));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            return Transaction.builder()
                    .id(1L).account(t.getAccount()).type(t.getType())
                    .amount(t.getAmount()).balanceAfter(t.getBalanceAfter()).status(t.getStatus())
                    .build();
        });

        TransactionResponse res = transactionService.create(1L,
                request(1L, TransactionType.DEPOSIT, BigDecimal.valueOf(200), BigDecimal.valueOf(1200), TransactionStatus.COMPLETED));

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.accountId()).isEqualTo(1L);
        assertThat(res.balanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(1200));
        assertThat(acc.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1200));
        verify(accountRepository).save(acc);
    }

    @Test
    void create_withdrawal_decreasesBalance() {
        Account acc = account(1L, BigDecimal.valueOf(1000));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(acc));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse res = transactionService.create(1L,
                request(1L, TransactionType.WITHDRAWAL, BigDecimal.valueOf(300), null, TransactionStatus.COMPLETED));

        assertThat(res.balanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(700));
        assertThat(acc.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(700));
    }

    @Test
    void create_withdrawal_insufficientFunds_throwsInvalidTransaction() {
        Account acc = account(1L, BigDecimal.valueOf(100));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(acc));

        assertThatThrownBy(() -> transactionService.create(1L,
                request(1L, TransactionType.WITHDRAWAL, BigDecimal.valueOf(500), null, TransactionStatus.COMPLETED)))
                .isInstanceOf(InvalidTransactionException.class);
    }

    @Test
    void create_accountNotFound_throwsException() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.create(99L,
                request(99L, TransactionType.DEPOSIT, BigDecimal.valueOf(100), null, TransactionStatus.COMPLETED)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Account");
    }

    @Test
    void getById_found_returnsResponse() {
        Account acc = account(1L, BigDecimal.valueOf(1000));
        Transaction t = transaction(1L, acc, TransactionType.DEPOSIT,
                BigDecimal.valueOf(200), BigDecimal.valueOf(1200), TransactionStatus.COMPLETED);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(t));

        TransactionResponse res = transactionService.getById(1L);

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.type()).isEqualTo(TransactionType.DEPOSIT);
    }

    @Test
    void getById_notFound_throwsException() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void list_byAccountId_returnsFiltered() {
        Account acc = account(1L, BigDecimal.valueOf(1000));
        Transaction t = transaction(1L, acc, TransactionType.DEPOSIT,
                BigDecimal.valueOf(200), BigDecimal.valueOf(1200), TransactionStatus.COMPLETED);
        when(transactionRepository.findByAccount_Id(1L)).thenReturn(List.of(t));

        List<TransactionResponse> res = transactionService.list(1L);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).accountId()).isEqualTo(1L);
    }

    @Test
    void list_byFilters_delegatesToRepositoryAndMapsResults() {
        Account acc = account(1L, BigDecimal.valueOf(1000));
        Transaction t = transaction(1L, acc, TransactionType.DEPOSIT,
                BigDecimal.valueOf(200), BigDecimal.valueOf(1200), TransactionStatus.COMPLETED);
        when(transactionRepository.findByFilters(TransactionStatus.COMPLETED, TransactionType.DEPOSIT, 1L))
                .thenReturn(List.of(t));

        List<TransactionResponse> res = transactionService.list(TransactionStatus.COMPLETED, TransactionType.DEPOSIT, 1L);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).id()).isEqualTo(1L);
        assertThat(res.get(0).status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(res.get(0).type()).isEqualTo(TransactionType.DEPOSIT);
        verify(transactionRepository).findByFilters(TransactionStatus.COMPLETED, TransactionType.DEPOSIT, 1L);
    }

    @Test
    void list_byFilters_noFilters_returnsAll() {
        Account acc = account(1L, BigDecimal.valueOf(1000));
        Transaction t1 = transaction(1L, acc, TransactionType.DEPOSIT,
                BigDecimal.valueOf(200), BigDecimal.valueOf(1200), TransactionStatus.COMPLETED);
        Transaction t2 = transaction(2L, acc, TransactionType.WITHDRAWAL,
                BigDecimal.valueOf(100), BigDecimal.valueOf(1100), TransactionStatus.PENDING);
        when(transactionRepository.findByFilters(null, null, null)).thenReturn(List.of(t1, t2));

        List<TransactionResponse> res = transactionService.list(null, null, null);

        assertThat(res).hasSize(2);
        verify(transactionRepository).findByFilters(null, null, null);
    }

    @Test
    void update_found_updatesAndReturns() {
        Account acc = account(1L, BigDecimal.valueOf(1000));
        Transaction existing = transaction(1L, acc, TransactionType.DEPOSIT,
                BigDecimal.valueOf(200), BigDecimal.valueOf(1200), TransactionStatus.COMPLETED);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(acc));
        when(accountRepository.save(any())).thenReturn(acc);
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse res = transactionService.update(1L,
                request(1L, TransactionType.DEPOSIT, BigDecimal.valueOf(300), null, TransactionStatus.COMPLETED));

        assertThat(res).isNotNull();
        verify(transactionRepository).save(existing);
    }

    @Test
    void update_transactionNotFound_throwsException() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.update(99L,
                request(1L, TransactionType.DEPOSIT, BigDecimal.valueOf(100), null, TransactionStatus.COMPLETED)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Transaction");
    }

    @Test
    void update_accountNotFound_throwsException() {
        Account acc = account(1L, BigDecimal.valueOf(1000));
        Transaction existing = transaction(1L, acc, TransactionType.DEPOSIT,
                BigDecimal.valueOf(200), BigDecimal.valueOf(1200), TransactionStatus.COMPLETED);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.update(1L,
                request(99L, TransactionType.DEPOSIT, BigDecimal.valueOf(100), null, TransactionStatus.COMPLETED)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Account");
    }

    @Test
    void delete_found_deletesById() {
        Account acc = account(1L, BigDecimal.valueOf(1000));
        Transaction t = transaction(1L, acc, TransactionType.DEPOSIT,
                BigDecimal.valueOf(200), BigDecimal.valueOf(1200), TransactionStatus.COMPLETED);
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
