package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.dto.TransactionStatusUpdateRequest;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.exception.InsufficientFundsException;
import com.techverito.banking.exception.InvalidTransactionStateException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@Transactional
public class TransactionService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public TransactionResponse create(Long accountId, TransactionRequest req) {
        Account account = findAccountOrThrow(accountId);

        return switch (req.type()) {
            case DEPOSIT -> TransactionResponse.from(deposit(account, req.amount()));
            case WITHDRAWAL -> TransactionResponse.from(withdraw(account, req.amount()));
            case TRANSFER -> TransactionResponse.from(transfer(account, req));
        };
    }

    private Transaction deposit(Account account, BigDecimal amount) {
        BigDecimal balanceAfter = account.getBalance().add(amount);
        account.setBalance(balanceAfter);
        accountRepository.save(account);

        Transaction transaction = Transaction.builder()
                .account(account)
                .type(TransactionType.DEPOSIT)
                .amount(amount)
                .balanceAfter(balanceAfter)
                .status(TransactionStatus.COMPLETED)
                .createdAt(Instant.now())
                .build();
        return transactionRepository.save(transaction);
    }

    private Transaction withdraw(Account account, BigDecimal amount) {
        if (account.getBalance().compareTo(amount) >= 0) {
            BigDecimal balanceAfter = account.getBalance().subtract(amount);
            account.setBalance(balanceAfter);
            accountRepository.save(account);

            Transaction transaction = Transaction.builder()
                    .account(account)
                    .type(TransactionType.WITHDRAWAL)
                    .amount(amount)
                    .balanceAfter(balanceAfter)
                    .status(TransactionStatus.COMPLETED)
                    .createdAt(Instant.now())
                    .build();
            return transactionRepository.save(transaction);
        }

        Transaction rejected = Transaction.builder()
                .account(account)
                .type(TransactionType.WITHDRAWAL)
                .amount(amount)
                .balanceAfter(account.getBalance())
                .status(TransactionStatus.REJECTED)
                .createdAt(Instant.now())
                .build();
        return transactionRepository.save(rejected);
    }

    private Transaction transfer(Account sourceAccount, TransactionRequest req) {
        if (req.targetAccountId() == null) {
            throw new IllegalArgumentException("targetAccountId is required for TRANSFER transactions");
        }
        Account targetAccount = findAccountOrThrow(req.targetAccountId());

        if (sourceAccount.getBalance().compareTo(req.amount()) < 0) {
            throw new InsufficientFundsException("Account " + sourceAccount.getId() + " has insufficient funds for this transfer");
        }

        UUID transferId = UUID.randomUUID();

        BigDecimal sourceBalanceAfter = sourceAccount.getBalance().subtract(req.amount());
        sourceAccount.setBalance(sourceBalanceAfter);
        accountRepository.save(sourceAccount);

        BigDecimal targetBalanceAfter = targetAccount.getBalance().add(req.amount());
        targetAccount.setBalance(targetBalanceAfter);
        accountRepository.save(targetAccount);

        Transaction withdrawalLeg = Transaction.builder()
                .account(sourceAccount)
                .type(TransactionType.WITHDRAWAL)
                .amount(req.amount())
                .balanceAfter(sourceBalanceAfter)
                .status(TransactionStatus.COMPLETED)
                .transferId(transferId)
                .createdAt(Instant.now())
                .build();
        transactionRepository.save(withdrawalLeg);

        Transaction depositLeg = Transaction.builder()
                .account(targetAccount)
                .type(TransactionType.DEPOSIT)
                .amount(req.amount())
                .balanceAfter(targetBalanceAfter)
                .status(TransactionStatus.COMPLETED)
                .transferId(transferId)
                .createdAt(Instant.now())
                .build();
        transactionRepository.save(depositLeg);

        return withdrawalLeg;
    }

    @Transactional(readOnly = true)
    public TransactionResponse getById(Long accountId, Long id) {
        return TransactionResponse.from(findTransactionOrThrow(accountId, id));
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> list(Long accountId, TransactionStatus status, Pageable pageable) {
        findAccountOrThrow(accountId);
        Page<Transaction> transactions = (status != null)
                ? transactionRepository.findByAccount_IdAndStatus(accountId, status, pageable)
                : transactionRepository.findByAccount_Id(accountId, pageable);
        return transactions.map(TransactionResponse::from);
    }

    public TransactionResponse updateStatus(Long accountId, Long id, TransactionStatusUpdateRequest req) {
        Transaction transaction = findTransactionOrThrow(accountId, id);

        if (transaction.getStatus() != TransactionStatus.COMPLETED || req.status() != TransactionStatus.CANCELED) {
            throw new InvalidTransactionStateException(
                    "Transaction " + id + " cannot transition from " + transaction.getStatus() + " to " + req.status());
        }

        Account account = transaction.getAccount();
        BigDecimal reversedBalance = (transaction.getType() == TransactionType.WITHDRAWAL)
                ? account.getBalance().add(transaction.getAmount())
                : account.getBalance().subtract(transaction.getAmount());
        account.setBalance(reversedBalance);
        accountRepository.save(account);

        transaction.setStatus(TransactionStatus.CANCELED);
        return TransactionResponse.from(transactionRepository.save(transaction));
    }

    public void delete(Long accountId, Long id) {
        Transaction transaction = findTransactionOrThrow(accountId, id);
        if (transaction.getStatus() != TransactionStatus.REJECTED) {
            throw new InvalidTransactionStateException(
                    "Transaction " + id + " cannot be deleted because its status is " + transaction.getStatus());
        }
        transactionRepository.deleteById(id);
    }

    private Account findAccountOrThrow(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }

    private Transaction findTransactionOrThrow(Long accountId, Long id) {
        Transaction transaction = transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
        if (!transaction.getAccount().getId().equals(accountId)) {
            throw new ResourceNotFoundException("Transaction", id);
        }
        return transaction;
    }
}
