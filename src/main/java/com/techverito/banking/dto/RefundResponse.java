package com.techverito.banking.dto;

import com.techverito.banking.entity.Refund;
import com.techverito.banking.entity.RefundStatus;

import java.math.BigDecimal;

public record RefundResponse(
        Long id,
        Long paymentId,
        String refundId,
        BigDecimal amount,
        String currency,
        RefundStatus status,
        String reason
) {
    public static RefundResponse from(Refund r) {
        return new RefundResponse(
                r.getId(), r.getPayment().getId(), r.getRefundId(),
                r.getAmount(), r.getCurrency(), r.getStatus(), r.getReason()
        );
    }
}
