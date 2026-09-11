package com.techverito.banking.service;

import com.techverito.banking.dto.RefundRequest;
import com.techverito.banking.dto.RefundResponse;
import com.techverito.banking.entity.Payment;
import com.techverito.banking.entity.PaymentStatus;
import com.techverito.banking.entity.Refund;
import com.techverito.banking.entity.RefundStatus;
import com.techverito.banking.exception.OverRefundException;
import com.techverito.banking.exception.PaymentNotRefundableException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.PaymentRepository;
import com.techverito.banking.repository.RefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

@Service
@Transactional
public class RefundService {

    private static final Set<PaymentStatus> REFUNDABLE_STATUSES =
            Set.of(PaymentStatus.CAPTURED, PaymentStatus.PARTIALLY_REFUNDED);

    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;

    public RefundService(PaymentRepository paymentRepository, RefundRepository refundRepository) {
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
    }

    /**
     * Refunds a payment, in full or partially, identified by the client-supplied refundId.
     * Idempotent: replaying the same (paymentId, refundId) pair returns the previously
     * recorded refund without performing the refund again.
     */
    public RefundResponse refund(Long paymentId, String refundId, RefundRequest req) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", paymentId));

        return refundRepository.findByPayment_IdAndRefundId(paymentId, refundId)
                .map(RefundResponse::from)
                .orElseGet(() -> processRefund(payment, refundId, req));
    }

    private RefundResponse processRefund(Payment payment, String refundId, RefundRequest req) {
        if (!REFUNDABLE_STATUSES.contains(payment.getStatus())) {
            throw new PaymentNotRefundableException(
                    "Payment " + payment.getId() + " is not in a refundable state: " + payment.getStatus());
        }

        BigDecimal alreadyRefunded = refundRepository.sumAmountByPayment_IdAndStatus(payment.getId(), RefundStatus.PROCESSED);
        BigDecimal remaining = payment.getTotalAmount().subtract(alreadyRefunded);

        BigDecimal amount = (req.amount() != null) ? req.amount() : remaining;

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be greater than zero");
        }

        if (amount.compareTo(remaining) > 0) {
            throw new OverRefundException(
                    "Refund amount " + amount + " exceeds remaining refundable amount " + remaining);
        }

        Refund refund = Refund.builder()
                .payment(payment)
                .refundId(refundId)
                .amount(amount)
                .currency(req.currency())
                .status(RefundStatus.PROCESSED)
                .reason(req.reason())
                .build();
        refund = refundRepository.save(refund);

        BigDecimal newRemaining = remaining.subtract(amount);
        payment.setStatus(newRemaining.compareTo(BigDecimal.ZERO) == 0
                ? PaymentStatus.REFUNDED
                : PaymentStatus.PARTIALLY_REFUNDED);
        paymentRepository.save(payment);

        return RefundResponse.from(refund);
    }
}
