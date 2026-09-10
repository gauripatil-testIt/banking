package com.techverito.banking.dto;

import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record TransactionRequest(
        @NotNull Long accountId,
        @NotNull TransactionType type,
        @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount,
        @NotNull BigDecimal balanceAfter,
        @NotNull TransactionStatus status
) {}
