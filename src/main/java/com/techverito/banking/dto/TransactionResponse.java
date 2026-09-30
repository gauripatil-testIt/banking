package com.techverito.banking.dto;

import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        Long id,
        Long accountId,
        TransactionType type,
        BigDecimal amount,
        BigDecimal resultingBalance,
        TransactionStatus status,
        String transferGroupId,
        Instant createdAt
) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(), t.getAccount().getId(), t.getType(),
                t.getAmount(), t.getResultingBalance(), t.getStatus(),
                t.getTransferGroupId(), t.getCreatedAt()
        );
    }
}
