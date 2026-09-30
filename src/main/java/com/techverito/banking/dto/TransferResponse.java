package com.techverito.banking.dto;

public record TransferResponse(
        TransactionResponse withdrawal,
        TransactionResponse deposit
) {}
