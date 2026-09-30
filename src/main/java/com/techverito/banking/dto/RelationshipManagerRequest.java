package com.techverito.banking.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record RelationshipManagerRequest(
        @NotBlank String name,
        @NotBlank @Email String email
) {}
