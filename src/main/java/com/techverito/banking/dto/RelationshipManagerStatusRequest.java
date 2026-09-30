package com.techverito.banking.dto;

import jakarta.validation.constraints.NotNull;

public record RelationshipManagerStatusRequest(
        @NotNull Boolean active
) {}
