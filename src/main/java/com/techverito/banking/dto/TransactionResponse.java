package com.techverito.banking.dto;

import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionResponse(
        Long id,
        Long accountId,
        TransactionType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        TransactionStatus status,
        String targetAccountNumber,
        LocalDateTime createdAt
) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(), t.getAccount().getId(), t.getType(),
                t.getAmount(), t.getBalanceAfter(), t.getStatus(),
                t.getTargetAccountNumber(), t.getCreatedAt()
        );
    }
}
