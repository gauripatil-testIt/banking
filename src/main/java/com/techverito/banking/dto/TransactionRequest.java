package com.techverito.banking.dto;

import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TransactionRequest(
        @NotNull Long accountId,
        @NotNull TransactionType type,
        @NotNull @DecimalMin("0.0") BigDecimal amount,
        @NotNull @DecimalMin("0.0") BigDecimal balanceAfter,
        @NotNull TransactionStatus status,
        String targetAccountNumber
) {}
