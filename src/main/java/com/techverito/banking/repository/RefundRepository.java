package com.techverito.banking.repository;

import com.techverito.banking.entity.Refund;
import com.techverito.banking.entity.RefundStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByPayment_IdAndRefundId(Long paymentId, String refundId);

    @Query("select coalesce(sum(r.amount), 0) from Refund r " +
            "where r.payment.id = :paymentId and r.status = :status")
    BigDecimal sumAmountByPayment_IdAndStatus(@Param("paymentId") Long paymentId,
                                               @Param("status") RefundStatus status);

    @Query("select coalesce(sum(r.amount), 0) from Refund r " +
            "where r.payment.id = :paymentId and r.status = 'PROCESSED'")
    BigDecimal sumProcessedAmountByPaymentId(@Param("paymentId") Long paymentId);
}
