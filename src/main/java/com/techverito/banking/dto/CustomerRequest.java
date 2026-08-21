package com.techverito.banking.dto;

import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.IdType;
import com.techverito.banking.validation.AgeAtLeast;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CustomerRequest(
        @NotBlank String firstName,
        @NotBlank String lastName,
        @Email @NotBlank String email,
        String phone,
        @NotNull CustomerStatus status,
        @NotBlank @Size(max = 50) String idNumber,
        IdType idType,
        @Past @AgeAtLeast(18) LocalDate dateOfBirth
) {}
