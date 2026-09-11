package com.techverito.banking.dto;

import java.math.BigDecimal;

public record OrderLineItemResponse(
        Long id,
        BigDecimal amount
) {}
