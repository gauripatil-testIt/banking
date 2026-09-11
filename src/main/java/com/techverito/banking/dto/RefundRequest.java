package com.techverito.banking.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record RefundRequest(
        BigDecimal amount,
        @NotBlank String currency,
        String reason
) {}
