package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.Transaction;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
        Transaction transaction = Transaction.builder()
                .account(account)
                .type(req.type())
                .amount(req.amount())
                .balanceAfter(req.balanceAfter())
                .status(req.status())
                .targetAccountNumber(req.targetAccountNumber())
                .createdAt(LocalDateTime.now())
                .build();
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
        Transaction transaction = findOrThrow(id);
        transaction.setType(req.type());
        transaction.setAmount(req.amount());
        transaction.setBalanceAfter(req.balanceAfter());
        transaction.setStatus(req.status());
        transaction.setTargetAccountNumber(req.targetAccountNumber());
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
}
