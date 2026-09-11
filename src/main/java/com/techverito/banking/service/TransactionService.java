package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionType;
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

    public TransactionResponse create(TransactionRequest req) {
        Account account = accountRepository.findById(req.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", req.accountId()));

        applyEffect(account, req.type(), req.amount());

        Transaction transaction = Transaction.builder()
                .account(account)
                .type(req.type())
                .amount(req.amount())
                .balanceAfter(req.balanceAfter())
                .status(req.status())
                .build();

        // Persist both: keep Account balance consistent and store Transaction record.
        accountRepository.save(account);
        return TransactionResponse.from(transactionRepository.save(transaction));
    }

    @Transactional(readOnly = true)
    public TransactionResponse getById(Long id) {
        return TransactionResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> list(Long accountId) {
        List<Transaction> transactions = (accountId != null)
                ? transactionRepository.findByAccount_Id(accountId)
                : transactionRepository.findAll();

        return transactions.stream().map(TransactionResponse::from).toList();
    }

    public TransactionResponse update(Long id, TransactionRequest req) {
        Transaction existingTransaction = findOrThrow(id);

        // Reverse the prior effect on the linked account.
        applyEffect(existingTransaction.getAccount(), existingTransaction.getType(), existingTransaction.getAmount().negate());

        // Resolve the (possibly new) account.
        Account targetAccount = accountRepository.findById(req.accountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", req.accountId()));

        // Apply the new effect to the target account.
        applyEffect(targetAccount, req.type(), req.amount());

        existingTransaction.setAccount(targetAccount);
        existingTransaction.setType(req.type());
        existingTransaction.setAmount(req.amount());
        existingTransaction.setBalanceAfter(req.balanceAfter());
        existingTransaction.setStatus(req.status());

        accountRepository.save(existingTransaction.getAccount());
        return TransactionResponse.from(transactionRepository.save(existingTransaction));
    }

    public void delete(Long id) {
        Transaction transaction = findOrThrow(id);

        // Reverse effect on linked account.
        applyEffect(transaction.getAccount(), transaction.getType(), transaction.getAmount().negate());
        accountRepository.save(transaction.getAccount());

        transactionRepository.deleteById(id);
    }

    private Transaction findOrThrow(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }

    private void applyEffect(Account account, TransactionType type, BigDecimal amount) {
        if (type == TransactionType.DEPOSIT) {
            account.setBalance(account.getBalance().add(amount));
        } else if (type == TransactionType.WITHDRAWAL || type == TransactionType.TRANSFER) {
            account.setBalance(account.getBalance().subtract(amount));
        }
    }
}
