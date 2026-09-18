package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionRequestType;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.dto.TransactionStatusUpdateRequest;
import com.techverito.banking.entity.*;
import com.techverito.banking.exception.InsufficientFundsException;
import com.techverito.banking.exception.InvalidTransactionStateException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    AccountRepository accountRepository;

    @Mock
    TransactionRepository transactionRepository;

    @InjectMocks
    TransactionService transactionService;

    private Account account(Long id, BigDecimal balance) {
        return Account.builder()
                .id(id)
                .accountNumber("ACC" + id)
                .type(AccountType.SAVINGS)
                .balance(balance)
                .status(AccountStatus.ACTIVE)
                .build();
    }

    private Transaction transaction(Long id, Account account, TransactionType type,
                                      BigDecimal amount, BigDecimal balanceAfter, TransactionStatus status) {
        return Transaction.builder()
                .id(id)
                .account(account)
                .type(type)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .status(status)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void withdrawal_exceedingBalance_persistsRejectedAndLeavesBalanceUnchanged() {
        Account account = account(1L, BigDecimal.valueOf(50));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionRequest req = new TransactionRequest(TransactionRequestType.WITHDRAWAL, BigDecimal.valueOf(100), null);

        TransactionResponse res = transactionService.create(1L, req);

        assertThat(res.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(res.balanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(50));
        verify(accountRepository, never()).save(any());
    }

    @Test
    void transfer_insufficientSourceFunds_persistsNothingAndThrows() {
        Account source = account(1L, BigDecimal.valueOf(50));
        Account target = account(2L, BigDecimal.valueOf(100));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(source));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(target));

        TransactionRequest req = new TransactionRequest(TransactionRequestType.TRANSFER, BigDecimal.valueOf(100), 2L);

        assertThatThrownBy(() -> transactionService.create(1L, req))
                .isInstanceOf(InsufficientFundsException.class);

        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        assertThat(source.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(target.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    void transfer_sufficientFunds_persistsTwoRowsSharingTransferIdAndUpdatesBothBalances() {
        Account source = account(1L, BigDecimal.valueOf(500));
        Account target = account(2L, BigDecimal.valueOf(100));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(source));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(target));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionRequest req = new TransactionRequest(TransactionRequestType.TRANSFER, BigDecimal.valueOf(200), 2L);

        TransactionResponse res = transactionService.create(1L, req);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(2)).save(captor.capture());
        List<Transaction> saved = captor.getAllValues();

        assertThat(saved).hasSize(2);
        assertThat(saved.get(0).getTransferId()).isNotNull();
        assertThat(saved.get(0).getTransferId()).isEqualTo(saved.get(1).getTransferId());

        assertThat(source.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(300));
        assertThat(target.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(300));

        assertThat(res.type()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(res.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(res.balanceAfter()).isEqualByComparingTo(BigDecimal.valueOf(300));
    }

    @Test
    void cancel_completedTransaction_reversesBalance_andCannotBeRepeated() {
        Account account = account(1L, BigDecimal.valueOf(100));
        Transaction transaction = transaction(10L, account, TransactionType.WITHDRAWAL,
                BigDecimal.valueOf(20), BigDecimal.valueOf(80), TransactionStatus.COMPLETED);
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(transaction));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse res = transactionService.updateStatus(1L, 10L,
                new TransactionStatusUpdateRequest(TransactionStatus.CANCELED));

        assertThat(res.status()).isEqualTo(TransactionStatus.CANCELED);
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(120));

        assertThatThrownBy(() -> transactionService.updateStatus(1L, 10L,
                new TransactionStatusUpdateRequest(TransactionStatus.CANCELED)))
                .isInstanceOf(InvalidTransactionStateException.class);
    }

    @Test
    void delete_completedTransaction_throwsInvalidTransactionStateException() {
        Account account = account(1L, BigDecimal.valueOf(100));
        Transaction transaction = transaction(10L, account, TransactionType.DEPOSIT,
                BigDecimal.valueOf(20), BigDecimal.valueOf(120), TransactionStatus.COMPLETED);
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.delete(1L, 10L))
                .isInstanceOf(InvalidTransactionStateException.class);

        verify(transactionRepository, never()).deleteById(any());
    }

    @Test
    void delete_rejectedTransaction_succeeds() {
        Account account = account(1L, BigDecimal.valueOf(100));
        Transaction transaction = transaction(10L, account, TransactionType.WITHDRAWAL,
                BigDecimal.valueOf(20), BigDecimal.valueOf(100), TransactionStatus.REJECTED);
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(transaction));

        transactionService.delete(1L, 10L);

        verify(transactionRepository).deleteById(10L);
    }

    @Test
    void getById_notFound_throwsException() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.getById(1L, 99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getById_belongsToDifferentAccount_throwsException() {
        Account account = account(1L, BigDecimal.valueOf(100));
        Transaction transaction = transaction(10L, account, TransactionType.DEPOSIT,
                BigDecimal.valueOf(20), BigDecimal.valueOf(120), TransactionStatus.COMPLETED);
        when(transactionRepository.findById(10L)).thenReturn(Optional.of(transaction));

        assertThatThrownBy(() -> transactionService.getById(2L, 10L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
