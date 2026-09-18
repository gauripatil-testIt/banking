package com.techverito.banking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record TransactionRequest(
        @NotNull TransactionRequestType type,
        @NotNull @Positive BigDecimal amount,
        Long targetAccountId
) {}
