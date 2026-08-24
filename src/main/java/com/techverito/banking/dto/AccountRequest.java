package com.techverito.banking.dto;

import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public record AccountRequest(
        @NotNull Long customerId,
        @NotBlank String accountNumber,
        @NotNull AccountType type,
        @NotNull @DecimalMin("0.0") BigDecimal balance,
        @NotNull AccountStatus status,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String currency
) {}
