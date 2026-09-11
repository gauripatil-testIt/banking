package com.techverito.banking.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;

import java.math.BigDecimal;
import java.util.List;

public record OrderRequest(
        @NotEmpty List<@Valid OrderLineItemRequest> lineItems,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal discountAmount
) {}
