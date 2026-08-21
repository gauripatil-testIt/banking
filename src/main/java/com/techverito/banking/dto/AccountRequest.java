package com.techverito.banking.dto;

import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.Currency;

public record AccountRequest(
        @NotNull Long customerId,
        @NotBlank String accountNumber,
        @NotNull AccountType type,
        @NotNull @DecimalMin("0.0") BigDecimal balance,
        @NotNull AccountStatus status,
        Currency currency
) {}
