package com.techverito.banking.dto;

import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.IdType;

import java.time.LocalDate;

public record CustomerResponse(
        Long id,
        String firstName,
        String lastName,
        String email,
        String phone,
        CustomerStatus status,
        String idNumber,
        IdType idType,
        LocalDate dateOfBirth,
        Long relationshipManagerId
) {
    public static CustomerResponse from(Customer c) {
        return new CustomerResponse(
                c.getId(), c.getFirstName(), c.getLastName(),
                c.getEmail(), c.getPhone(), c.getStatus(),
                c.getIdNumber(), c.getIdType(), c.getDateOfBirth(),
                c.getRelationshipManagerId()
        );
    }
}
