package com.techverito.banking.dto;

import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerStatus;

public record CustomerResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        CustomerStatus status
) {
    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(
                c.getId(), c.getFirstName(), c.getLastName(),
                c.getEmail(), c.getPhone(), c.getStatus()
        );
    }
}
