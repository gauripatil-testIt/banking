package com.techverito.banking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record BeneficiaryRequest(
        @NotNull Long customerId,
        @NotBlank String name,
        @NotBlank String accountNumber,
        @NotBlank String bankName,
        @NotBlank String beneficiaryType
) {}
