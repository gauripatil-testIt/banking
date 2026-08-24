package com.techverito.banking.dto;

import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;

import java.math.BigDecimal;

public record AccountResponse(
        Long id,
        Long customerId,
        String accountNumber,
        AccountType type,
        BigDecimal balance,
        AccountStatus status,
        String currency
) {
    public static AccountResponse from(Account a) {
        return new AccountResponse(
                a.getId(), a.getCustomer().getId(), a.getAccountNumber(),
                a.getType(), a.getBalance(), a.getStatus(), a.getCurrency()
        );
    }
}
