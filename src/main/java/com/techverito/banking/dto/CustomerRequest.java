package com.techverito.banking.dto;

import com.techverito.banking.entity.CustomerStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CustomerRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @Email @NotBlank String email,
        String phone,
        @NotNull CustomerStatus status,
        String idNumber,
        String idType,
        LocalDate dateOfBirth
) {
    public CustomerRequest(String firstName, String lastName, String email, String phone, CustomerStatus status) {
        this(firstName, lastName, email, phone, status, null, null, null);
    }
}
