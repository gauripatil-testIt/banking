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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundServiceTest {

    @Mock
    PaymentRepository paymentRepository;

    @Mock
    RefundRepository refundRepository;

    @InjectMocks
    RefundService refundService;

    private Payment payment(Long id, BigDecimal totalAmount, PaymentStatus status) {
        return Payment.builder()
                .id(id).totalAmount(totalAmount).currency("USD").status(status)
                .build();
    }

    private Refund refund(Long id, Payment payment, String refundId, BigDecimal amount, RefundStatus status) {
        return Refund.builder()
                .id(id).payment(payment).refundId(refundId).amount(amount)
                .currency("USD").status(status).reason("reason")
                .build();
    }

    private RefundRequest request(BigDecimal amount) {
        return new RefundRequest(amount, "USD", "customer request");
    }

    @Test
    void refund_sameRefundIdTwice_processesOnlyOnce() {
        Payment payment = payment(1L, BigDecimal.valueOf(100), PaymentStatus.CAPTURED);
        Refund saved = refund(1L, payment, "refund-1", BigDecimal.valueOf(100), RefundStatus.PROCESSED);

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPayment_IdAndRefundId(1L, "refund-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(saved));
        when(refundRepository.sumProcessedAmountByPaymentId(1L)).thenReturn(BigDecimal.ZERO);
        when(refundRepository.save(any())).thenReturn(saved);

        RefundResponse first = refundService.refund(1L, "refund-1", request(BigDecimal.valueOf(100)));
        RefundResponse second = refundService.refund(1L, "refund-1", request(BigDecimal.valueOf(100)));

        assertThat(first.refundId()).isEqualTo("refund-1");
        assertThat(second).isEqualTo(first);
        verify(refundRepository, times(1)).save(any());
    }

    @Test
    void refund_partialAmount_leavesRemainingRefundable() {
        Payment payment = payment(1L, BigDecimal.valueOf(100), PaymentStatus.CAPTURED);
        Refund saved = refund(1L, payment, "refund-1", BigDecimal.valueOf(75), RefundStatus.PROCESSED);

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPayment_IdAndRefundId(1L, "refund-1")).thenReturn(Optional.empty());
        when(refundRepository.sumProcessedAmountByPaymentId(1L)).thenReturn(BigDecimal.ZERO);
        when(refundRepository.save(any())).thenReturn(saved);

        RefundResponse res = refundService.refund(1L, "refund-1", request(BigDecimal.valueOf(75)));

        assertThat(res.amount()).isEqualByComparingTo(BigDecimal.valueOf(75));

        ArgumentCaptor<Payment> paymentCaptor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(paymentCaptor.capture());
        assertThat(paymentCaptor.getValue().getStatus()).isEqualTo(PaymentStatus.PARTIALLY_REFUNDED);
    }

    @Test
    void refund_exceedsRemaining_throwsOverRefundException() {
        Payment payment = payment(1L, BigDecimal.valueOf(100), PaymentStatus.CAPTURED);

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPayment_IdAndRefundId(1L, "refund-1")).thenReturn(Optional.empty());
        when(refundRepository.sumProcessedAmountByPaymentId(1L)).thenReturn(BigDecimal.valueOf(30));

        assertThatThrownBy(() -> refundService.refund(1L, "refund-1", request(BigDecimal.valueOf(75))))
                .isInstanceOf(OverRefundException.class);

        verify(refundRepository, never()).save(any());
    }

    @Test
    void refund_paymentNotFound_throwsResourceNotFoundException() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refundService.refund(99L, "refund-1", request(BigDecimal.valueOf(10))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void refund_paymentNotRefundableStatus_throwsPaymentNotRefundableException() {
        Payment payment = payment(1L, BigDecimal.valueOf(100), PaymentStatus.NOT_REFUNDABLE);

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPayment_IdAndRefundId(1L, "refund-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> refundService.refund(1L, "refund-1", request(BigDecimal.valueOf(10))))
                .isInstanceOf(PaymentNotRefundableException.class);
    }

    @Test
    void refund_zeroOrNegativeAmount_throwsIllegalArgumentException() {
        Payment payment = payment(1L, BigDecimal.valueOf(100), PaymentStatus.CAPTURED);

        when(paymentRepository.findById(1L)).thenReturn(Optional.of(payment));
        when(refundRepository.findByPayment_IdAndRefundId(1L, "refund-1")).thenReturn(Optional.empty());
        when(refundRepository.sumProcessedAmountByPaymentId(1L)).thenReturn(BigDecimal.ZERO);

        assertThatThrownBy(() -> refundService.refund(1L, "refund-1", request(BigDecimal.ZERO)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> refundService.refund(1L, "refund-1", request(BigDecimal.valueOf(-5))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
