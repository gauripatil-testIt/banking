package com.techverito.banking.service;

import com.techverito.banking.dto.PagedResponse;
import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.dto.TransferRequest;
import com.techverito.banking.dto.TransferResponse;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.exception.InsufficientFundsException;
import com.techverito.banking.exception.InvalidTransactionStateException;
import com.techverito.banking.exception.InvalidTransactionTypeException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.TransactionRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class TransactionService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(AccountRepository accountRepository, TransactionRepository transactionRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    public TransactionResponse recordTransaction(Long accountId, TransactionRequest req) {
        if (req.type() == TransactionType.TRANSFER) {
            throw new InvalidTransactionTypeException("Transfers must be submitted via POST /transfers");
        }

        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));

        if (req.type() == TransactionType.DEPOSIT) {
            BigDecimal newBalance = account.getBalance().add(req.amount());
            account.setBalance(newBalance);
            accountRepository.save(account);

            Transaction transaction = Transaction.builder()
                    .account(account)
                    .type(TransactionType.DEPOSIT)
                    .amount(req.amount())
                    .resultingBalance(newBalance)
                    .status(TransactionStatus.COMPLETED)
                    .build();
            return TransactionResponse.from(transactionRepository.save(transaction));
        }

        // WITHDRAWAL
        if (account.getBalance().compareTo(req.amount()) < 0) {
            Transaction rejected = Transaction.builder()
                    .account(account)
                    .type(TransactionType.WITHDRAWAL)
                    .amount(req.amount())
                    .resultingBalance(account.getBalance())
                    .status(TransactionStatus.REJECTED)
                    .build();
            return TransactionResponse.from(transactionRepository.save(rejected));
        }

        BigDecimal newBalance = account.getBalance().subtract(req.amount());
        account.setBalance(newBalance);
        accountRepository.save(account);

        Transaction transaction = Transaction.builder()
                .account(account)
                .type(TransactionType.WITHDRAWAL)
                .amount(req.amount())
                .resultingBalance(newBalance)
                .status(TransactionStatus.COMPLETED)
                .build();
        return TransactionResponse.from(transactionRepository.save(transaction));
    }

    public TransferResponse transfer(TransferRequest req) {
        Account source = accountRepository.findById(req.sourceAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", req.sourceAccountId()));
        Account destination = accountRepository.findById(req.destinationAccountId())
                .orElseThrow(() -> new ResourceNotFoundException("Account", req.destinationAccountId()));

        if (source.getBalance().compareTo(req.amount()) < 0) {
            throw new InsufficientFundsException(source.getId());
        }

        String transferGroupId = UUID.randomUUID().toString();

        BigDecimal sourceNewBalance = source.getBalance().subtract(req.amount());
        source.setBalance(sourceNewBalance);
        accountRepository.save(source);

        BigDecimal destinationNewBalance = destination.getBalance().add(req.amount());
        destination.setBalance(destinationNewBalance);
        accountRepository.save(destination);

        Transaction withdrawal = Transaction.builder()
                .account(source)
                .type(TransactionType.WITHDRAWAL)
                .amount(req.amount())
                .resultingBalance(sourceNewBalance)
                .status(TransactionStatus.COMPLETED)
                .transferGroupId(transferGroupId)
                .build();
        Transaction deposit = Transaction.builder()
                .account(destination)
                .type(TransactionType.DEPOSIT)
                .amount(req.amount())
                .resultingBalance(destinationNewBalance)
                .status(TransactionStatus.COMPLETED)
                .transferGroupId(transferGroupId)
                .build();

        Transaction savedWithdrawal = transactionRepository.save(withdrawal);
        Transaction savedDeposit = transactionRepository.save(deposit);

        return new TransferResponse(TransactionResponse.from(savedWithdrawal), TransactionResponse.from(savedDeposit));
    }

    public TransactionResponse cancel(Long id) {
        Transaction transaction = findOrThrow(id);
        if (transaction.getStatus() != TransactionStatus.COMPLETED) {
            throw new InvalidTransactionStateException("Only completed transactions can be cancelled");
        }

        reverseAndCancel(transaction);

        if (transaction.getTransferGroupId() != null) {
            List<Transaction> linkedLegs = transactionRepository
                    .findByTransferGroupIdAndIdNot(transaction.getTransferGroupId(), transaction.getId());
            for (Transaction linked : linkedLegs) {
                if (linked.getStatus() == TransactionStatus.COMPLETED) {
                    reverseAndCancel(linked);
                }
            }
        }

        return TransactionResponse.from(transaction);
    }

    public void delete(Long id) {
        Transaction transaction = findOrThrow(id);
        if (transaction.getStatus() != TransactionStatus.REJECTED) {
            throw new InvalidTransactionStateException(
                    "Only rejected transactions can be deleted; cancel completed or cancelled transactions instead");
        }
        transactionRepository.deleteById(id);
    }

    @Transactional(readOnly = true)
    public PagedResponse<TransactionResponse> history(Long accountId, TransactionStatus status, int page, int size) {
        accountRepository.findById(accountId)
                .orElseThrow(() -> new ResourceNotFoundException("Account", accountId));

        int pageNumber = Math.max(page, 0);
        int pageSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(pageNumber, pageSize);

        Page<Transaction> result = (status != null)
                ? transactionRepository.findByAccount_IdAndStatusOrderByCreatedAtDesc(accountId, status, pageable)
                : transactionRepository.findByAccount_IdOrderByCreatedAtDesc(accountId, pageable);

        List<TransactionResponse> items = result.getContent().stream()
                .map(TransactionResponse::from)
                .toList();

        return new PagedResponse<>(items, result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private void reverseAndCancel(Transaction transaction) {
        Account account = transaction.getAccount();
        BigDecimal reversedBalance = (transaction.getType() == TransactionType.WITHDRAWAL)
                ? account.getBalance().add(transaction.getAmount())
                : account.getBalance().subtract(transaction.getAmount());
        account.setBalance(reversedBalance);
        accountRepository.save(account);

        transaction.markCancelled();
        transactionRepository.save(transaction);
    }

    private Transaction findOrThrow(Long id) {
        return transactionRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction", id));
    }
}
