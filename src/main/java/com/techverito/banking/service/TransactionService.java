package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.exception.InvalidTransactionException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@Transactional
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;

    public TransactionService(TransactionRepository transactionRepository, AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
    }

    public TransactionResponse create(Long accountId, TransactionRequest req) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));

        BigDecimal balanceAfter = applyToAccount(account, req.type(), req.amount());

        Transaction transaction = Transaction.builder()
                .account(account)
                .type(req.type())
                .amount(req.amount())
                .balanceAfter(balanceAfter)
                .status(req.status())
                .build();

        Transaction saved = transactionRepository.save(transaction);
        accountRepository.save(account);
        return TransactionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public TransactionResponse getById(Long id) {
        return TransactionResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> list(Long accountId) {
        return transactionRepository.findByAccount_Id(accountId).stream()
                .map(TransactionResponse::from)
                .toList();
    }

    public TransactionResponse update(Long id, TransactionRequest req) {
        Transaction transaction = findOrThrow(id);

        Account oldAccount = transaction.getAccount();
        revertFromAccount(oldAccount, transaction.getType(), transaction.getAmount());
        accountRepository.save(oldAccount);

        Account newAccount = accountRepository.findById(req.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", req.accountId()));

        BigDecimal balanceAfter = applyToAccount(newAccount, req.type(), req.amount());
        accountRepository.save(newAccount);

        transaction.setAccount(newAccount);
        transaction.setType(req.type());
        transaction.setAmount(req.amount());
        transaction.setBalanceAfter(balanceAfter);
        transaction.setStatus(req.status());

        return TransactionResponse.from(transactionRepository.save(transaction));
    }

    public void delete(Long id) {
        findOrThrow(id);
        transactionRepository.deleteById(id);
    }

    private Transaction findOrThrow(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }

    private BigDecimal applyToAccount(Account account, TransactionType type, BigDecimal amount) {
        BigDecimal newBalance = switch (type) {
            case DEPOSIT -> account.getBalance().add(amount);
            case WITHDRAWAL, TRANSFER -> account.getBalance().subtract(amount);
        };

        if (newBalance.compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidTransactionException("Insufficient funds for transaction on account " + account.getId());
        }

        account.setBalance(newBalance);
        return newBalance;
    }

    private void revertFromAccount(Account account, TransactionType type, BigDecimal amount) {
        BigDecimal restoredBalance = switch (type) {
            case DEPOSIT -> account.getBalance().subtract(amount);
            case WITHDRAWAL, TRANSFER -> account.getBalance().add(amount);
        };
        account.setBalance(restoredBalance);
    }
}
