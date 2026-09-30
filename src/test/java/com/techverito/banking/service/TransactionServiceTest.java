package com.techverito.banking.service;

import com.techverito.banking.dto.PagedResponse;
import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.dto.TransferRequest;
import com.techverito.banking.dto.TransferResponse;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.exception.InsufficientFundsException;
import com.techverito.banking.exception.InvalidTransactionStateException;
import com.techverito.banking.exception.InvalidTransactionTypeException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    AccountRepository accountRepository;

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

    private Account account(Long id, BigDecimal balance) {
        return Account.builder()
                .id(id).customer(customer(1L))
                .accountNumber("ACC" + id).type(AccountType.SAVINGS)
                .balance(balance).status(AccountStatus.ACTIVE)
                .build();
    }

    private Transaction transaction(Long id, Account account, TransactionType type, BigDecimal amount,
                                     BigDecimal resultingBalance, TransactionStatus status, String transferGroupId) {
        return Transaction.builder()
                .id(id).account(account).type(type).amount(amount)
                .resultingBalance(resultingBalance).status(status)
                .transferGroupId(transferGroupId)
                .build();
    }

    @Test
    void recordTransaction_deposit_increasesBalance() {
        Account account = account(1L, BigDecimal.valueOf(1000));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any())).thenAnswer(inv -> {
            Transaction t = inv.getArgument(0);
            return Transaction.builder()
                    .id(10L).account(t.getAccount()).type(t.getType()).amount(t.getAmount())
                    .resultingBalance(t.getResultingBalance()).status(t.getStatus())
                    .transferGroupId(t.getTransferGroupId()).build();
        });

        TransactionResponse res = transactionService.recordTransaction(1L, new TransactionRequest(TransactionType.DEPOSIT, BigDecimal.valueOf(200)));

        assertThat(res.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(res.resultingBalance()).isEqualByComparingTo(BigDecimal.valueOf(1200));
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1200));
        verify(accountRepository).save(account);
    }

    @Test
    void recordTransaction_withdrawal_sufficientFunds_decreasesBalance() {
        Account account = account(1L, BigDecimal.valueOf(1000));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse res = transactionService.recordTransaction(1L, new TransactionRequest(TransactionType.WITHDRAWAL, BigDecimal.valueOf(300)));

        assertThat(res.status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(res.resultingBalance()).isEqualByComparingTo(BigDecimal.valueOf(700));
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(700));
    }

    @Test
    void recordTransaction_withdrawal_insufficientFunds_rejectsAndKeepsBalance() {
        Account account = account(1L, BigDecimal.valueOf(100));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse res = transactionService.recordTransaction(1L, new TransactionRequest(TransactionType.WITHDRAWAL, BigDecimal.valueOf(500)));

        assertThat(res.status()).isEqualTo(TransactionStatus.REJECTED);
        assertThat(res.resultingBalance()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(100));
        verify(accountRepository, never()).save(any());

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(TransactionStatus.REJECTED);
    }

    @Test
    void recordTransaction_transferType_throwsInvalidTransactionTypeException() {
        assertThatThrownBy(() -> transactionService.recordTransaction(1L,
                new TransactionRequest(TransactionType.TRANSFER, BigDecimal.valueOf(50))))
                .isInstanceOf(InvalidTransactionTypeException.class);
        verifyNoInteractions(accountRepository, transactionRepository);
    }

    @Test
    void transfer_success_movesFundsAndLinksLegs() {
        Account source = account(1L, BigDecimal.valueOf(1000));
        Account destination = account(2L, BigDecimal.valueOf(500));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(source));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(destination));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferResponse res = transactionService.transfer(new TransferRequest(1L, 2L, BigDecimal.valueOf(200)));

        assertThat(res.withdrawal().type()).isEqualTo(TransactionType.WITHDRAWAL);
        assertThat(res.deposit().type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(res.withdrawal().transferGroupId()).isEqualTo(res.deposit().transferGroupId());
        assertThat(res.withdrawal().resultingBalance()).isEqualByComparingTo(BigDecimal.valueOf(800));
        assertThat(res.deposit().resultingBalance()).isEqualByComparingTo(BigDecimal.valueOf(700));
        assertThat(source.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(800));
        assertThat(destination.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(700));
    }

    @Test
    void transfer_missingAccount_throwsAndPersistsNothing() {
        when(accountRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.transfer(new TransferRequest(1L, 2L, BigDecimal.valueOf(200))))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
    }

    @Test
    void transfer_insufficientFunds_throwsAndPersistsNothing() {
        Account source = account(1L, BigDecimal.valueOf(50));
        Account destination = account(2L, BigDecimal.valueOf(500));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(source));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(destination));

        assertThatThrownBy(() -> transactionService.transfer(new TransferRequest(1L, 2L, BigDecimal.valueOf(200))))
                .isInstanceOf(InsufficientFundsException.class);

        verify(accountRepository, never()).save(any());
        verifyNoInteractions(transactionRepository);
        assertThat(source.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(destination.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(500));
    }

    @Test
    void cancel_completedWithdrawal_reversesBalance() {
        Account account = account(1L, BigDecimal.valueOf(700));
        Transaction t = transaction(1L, account, TransactionType.WITHDRAWAL, BigDecimal.valueOf(300),
                BigDecimal.valueOf(700), TransactionStatus.COMPLETED, null);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(t));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse res = transactionService.cancel(1L);

        assertThat(res.status()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        verify(accountRepository).save(account);
    }

    @Test
    void cancel_alreadyCancelled_throwsInvalidTransactionStateException() {
        Account account = account(1L, BigDecimal.valueOf(700));
        Transaction t = transaction(1L, account, TransactionType.WITHDRAWAL, BigDecimal.valueOf(300),
                BigDecimal.valueOf(700), TransactionStatus.CANCELLED, null);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> transactionService.cancel(1L))
                .isInstanceOf(InvalidTransactionStateException.class);
    }

    @Test
    void cancel_transferLeg_cancelsBothLegs() {
        Account source = account(1L, BigDecimal.valueOf(800));
        Account destination = account(2L, BigDecimal.valueOf(700));
        Transaction withdrawal = transaction(1L, source, TransactionType.WITHDRAWAL, BigDecimal.valueOf(200),
                BigDecimal.valueOf(800), TransactionStatus.COMPLETED, "group-1");
        Transaction deposit = transaction(2L, destination, TransactionType.DEPOSIT, BigDecimal.valueOf(200),
                BigDecimal.valueOf(700), TransactionStatus.COMPLETED, "group-1");

        when(transactionRepository.findById(1L)).thenReturn(Optional.of(withdrawal));
        when(transactionRepository.findByTransferGroupIdAndIdNot("group-1", 1L)).thenReturn(List.of(deposit));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse res = transactionService.cancel(1L);

        assertThat(res.status()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(withdrawal.getStatus()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(deposit.getStatus()).isEqualTo(TransactionStatus.CANCELLED);
        assertThat(source.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(1000));
        assertThat(destination.getBalance()).isEqualByComparingTo(BigDecimal.valueOf(500));
    }

    @Test
    void delete_rejected_deletesById() {
        Account account = account(1L, BigDecimal.valueOf(1000));
        Transaction t = transaction(1L, account, TransactionType.WITHDRAWAL, BigDecimal.valueOf(300),
                BigDecimal.valueOf(1000), TransactionStatus.REJECTED, null);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(t));

        transactionService.delete(1L);

        verify(transactionRepository).deleteById(1L);
    }

    @Test
    void delete_completed_throwsInvalidTransactionStateException() {
        Account account = account(1L, BigDecimal.valueOf(1000));
        Transaction t = transaction(1L, account, TransactionType.WITHDRAWAL, BigDecimal.valueOf(300),
                BigDecimal.valueOf(700), TransactionStatus.COMPLETED, null);
        when(transactionRepository.findById(1L)).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> transactionService.delete(1L))
                .isInstanceOf(InvalidTransactionStateException.class)
                .hasMessageContaining("cancel");

        verify(transactionRepository, never()).deleteById(any());
    }

    @Test
    void history_returnsPagedResponse() {
        Account account = account(1L, BigDecimal.valueOf(1000));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));
        Transaction t1 = transaction(1L, account, TransactionType.DEPOSIT, BigDecimal.valueOf(100),
                BigDecimal.valueOf(1000), TransactionStatus.COMPLETED, null);
        Page<Transaction> page = new PageImpl<>(List.of(t1), PageRequest.of(0, 20), 1);
        when(transactionRepository.findByAccount_IdOrderByCreatedAtDesc(eq(1L), any())).thenReturn(page);

        PagedResponse<TransactionResponse> res = transactionService.history(1L, null, 0, 20);

        assertThat(res.items()).hasSize(1);
        assertThat(res.pageNumber()).isEqualTo(0);
        assertThat(res.pageSize()).isEqualTo(20);
        assertThat(res.totalElements()).isEqualTo(1);
    }

    @Test
    void history_accountNotFound_throwsException() {
        when(accountRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.history(99L, null, 0, 20))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
