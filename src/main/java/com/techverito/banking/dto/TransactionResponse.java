package com.techverito.banking.dto;

import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(
        Long id,
        Long customerId,
        TransactionType type,
        TransactionStatus status,
        BigDecimal amount,
        Instant occurredAt
) {
    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(), t.getCustomer().getId(), t.getType(),
                t.getStatus(), t.getAmount(), t.getOccurredAt()
        );
    }
}
