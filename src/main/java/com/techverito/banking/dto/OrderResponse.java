package com.techverito.banking.dto;

import com.techverito.banking.entity.Order;

import java.math.BigDecimal;
import java.util.List;
	
public record OrderResponse(
        Long id,
        List<OrderLineItemResponse> lineItems,
        BigDecimal discountAmount,
        BigDecimal total
) {
    public static OrderResponse from(Order o) {
        return new OrderResponse(
                o.getId(),
                o.getLineItems().stream().map(li -> new OrderLineItemResponse(li.getId(), li.getAmount())).toList(),
                o.getDiscountAmount(),
                o.getTotal()
        );
    }
}
