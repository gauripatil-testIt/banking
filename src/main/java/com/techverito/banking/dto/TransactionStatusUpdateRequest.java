package com.techverito.banking.dto;

import com.techverito.banking.entity.TransactionStatus;
import jakarta.validation.constraints.NotNull;

public record TransactionStatusUpdateRequest(
        @NotNull TransactionStatus status
) {}
