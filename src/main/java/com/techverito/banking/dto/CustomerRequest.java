package com.techverito.banking.dto;

import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.IdType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CustomerRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @Email @NotBlank String email,
        String phone,
        @NotNull CustomerStatus status,
        @NotBlank @Size(max = 32) String idNumber,
        @NotNull IdType idType,
        LocalDate dateOfBirth
) {}
