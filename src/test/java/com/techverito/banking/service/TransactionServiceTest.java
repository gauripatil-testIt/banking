package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.entity.Transaction;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
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
        Account acc = new Account();
        acc.setId(id);
        acc.setAccountNumber("ACC001");
        acc.setType(AccountType.SAVINGS);
        acc.setBalance(balance);
        acc.setStatus(AccountStatus.ACTIVE);
        // Customer is not required for balance-effect tests
        return acc;
    }

    private Transaction transaction(Long id, Long accountId, TransactionType type, BigDecimal amount, BigDecimal balanceAfter, TransactionStatus status) {
        Transaction t = new Transaction();
        t.setId(id);
        Account acc = new Account();
        acc.setId(accountId);
        t.setAccount(acc);
        t.setType(type);
        t.setAmount(amount);
        t.setBalanceAfter(balanceAfter);
        t.setStatus(status);
        return t;
    }

    private TransactionRequest request(Long accountId, TransactionType type, BigDecimal amount, BigDecimal balanceAfter, TransactionStatus status) {
        return new TransactionRequest(accountId, type, amount, balanceAfter, status);
    }

    private TransactionResponse response(Long id, Long accountId, TransactionType type, BigDecimal amount, BigDecimal balanceAfter, TransactionStatus status) {
        return new TransactionResponse(id, accountId, type, amount, balanceAfter, status);
    }

    @Test
    void create_deposit_increasesAccountBalance() {
        Long accountId = 1L;
        BigDecimal startingBalance = BigDecimal.valueOf(100);
        BigDecimal amount = BigDecimal.valueOf(50);
        BigDecimal balanceAfter = BigDecimal.valueOf(150);

        Account acc = account(accountId, startingBalance);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(acc));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction t = invocation.getArgument(0);
            t.setId(10L);
            return t;
        });

        TransactionRequest req = request(accountId, TransactionType.DEPOSIT, amount, balanceAfter, TransactionStatus.COMPLETED);

        TransactionResponse res = transactionService.create(req);

        assertEquals(10L, res.id());
        assertEquals(accountId, res.accountId());
        assertEquals(TransactionType.DEPOSIT, res.type());
        assertEquals(balanceAfter, res.balanceAfter());
        assertEquals(startingBalance.add(amount), acc.getBalance());

        verify(accountRepository).save(acc);
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void create_withdrawal_decreasesAccountBalance() {
        Long accountId = 1L;
        BigDecimal startingBalance = BigDecimal.valueOf(100);
        BigDecimal amount = BigDecimal.valueOf(40);
        BigDecimal balanceAfter = BigDecimal.valueOf(60);

        Account acc = account(accountId, startingBalance);
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(acc));
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> {
            Transaction t = invocation.getArgument(0);
            t.setId(10L);
            return t;
        });

        TransactionRequest req = request(accountId, TransactionType.WITHDRAWAL, amount, balanceAfter, TransactionStatus.COMPLETED);

        TransactionResponse res = transactionService.create(req);

        assertEquals(10L, res.id());
        assertEquals(TransactionType.WITHDRAWAL, res.type());
        assertEquals(balanceAfter, res.balanceAfter());
        assertEquals(startingBalance.subtract(amount), acc.getBalance());

        verify(accountRepository).save(acc);
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void getById_notFound_throwsResourceNotFoundException() {
        when(transactionRepository.findById(99L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> transactionService.getById(99L));
    }

    @Test
    void update_reversesOldEffectAndAppliesNewEffect() {
        Long txId = 1L;
        Long accountId = 1L;

        BigDecimal startingBalance = BigDecimal.valueOf(100);
        Account acc = account(accountId, startingBalance);

        Transaction oldTx = transaction(txId, accountId, TransactionType.DEPOSIT, BigDecimal.valueOf(20), BigDecimal.valueOf(120), TransactionStatus.COMPLETED);
        when(transactionRepository.findById(txId)).thenReturn(Optional.of(oldTx));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(acc));

        TransactionRequest req = request(accountId, TransactionType.WITHDRAWAL, BigDecimal.valueOf(30), BigDecimal.valueOf(90), TransactionStatus.COMPLETED);

        when(transactionRepository.save(any(Transaction.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TransactionResponse res = transactionService.update(txId, req);

        assertEquals(txId, res.id());
        assertEquals(TransactionType.WITHDRAWAL, res.type());
        // reverse deposit: -20? Actually reversing prior effect means subtracting the old effect of DEPOSIT (i.e., subtract amount)
        // then apply new effect: WITHDRAWAL subtract 30
        // starting 100 -> apply old reverse (-20) => 80 -> apply new (-30) => 50
        assertEquals(BigDecimal.valueOf(50), acc.getBalance());

        verify(accountRepository).save(acc);
        verify(transactionRepository).save(any(Transaction.class));
    }

    @Test
    void delete_reversesEffect() {
        Long txId = 1L;
        Long accountId = 1L;

        Account acc = account(accountId, BigDecimal.valueOf(100));
        Transaction tx = transaction(txId, accountId, TransactionType.WITHDRAWAL, BigDecimal.valueOf(25), BigDecimal.valueOf(75), TransactionStatus.COMPLETED);

        when(transactionRepository.findById(txId)).thenReturn(Optional.of(tx));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(acc));

        doNothing().when(transactionRepository).deleteById(txId);

        transactionService.delete(txId);

        // reverse withdrawal: add amount back
        assertEquals(BigDecimal.valueOf(125), acc.getBalance());
        verify(accountRepository).save(acc);
        verify(transactionRepository).deleteById(txId);
    }

    @Test
    void list_withAccountId_filtersByAccount() {
        Long accountId = 1L;
        when(transactionRepository.findByAccount_Id(accountId)).thenReturn(List.of(
                transaction(1L, accountId, TransactionType.DEPOSIT, BigDecimal.valueOf(10), BigDecimal.valueOf(110), TransactionStatus.COMPLETED),
                transaction(2L, accountId, TransactionType.WITHDRAWAL, BigDecimal.valueOf(5), BigDecimal.valueOf(105), TransactionStatus.COMPLETED)
        ));

        List<TransactionResponse> res = transactionService.list(accountId);

        assertEquals(2, res.size());
        assertTrue(res.stream().allMatch(r -> r.accountId().equals(accountId)));
    }

    @Test
    void update_unknownTransaction_throws() {
        when(transactionRepository.findById(123L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> transactionService.update(123L, request(1L, TransactionType.DEPOSIT, BigDecimal.TEN, BigDecimal.TEN, TransactionStatus.PENDING)));
    }

    @Test
    void create_unknownAccount_throws() {
        when(accountRepository.findById(1L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class, () -> transactionService.create(request(1L, TransactionType.DEPOSIT, BigDecimal.TEN, BigDecimal.TEN, TransactionStatus.PENDING)));
    }
}
