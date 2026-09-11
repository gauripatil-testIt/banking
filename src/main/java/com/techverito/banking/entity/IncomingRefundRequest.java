package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Represents a refund request event sent by the client, persisted so it can be picked up
 * and processed by {@code RefundRequestPoller} even if the server was down when the client
 * originally sent it. Idempotency is ultimately enforced by RefundService via
 * (paymentId, refundId), so this table may be processed more than once safely.
 */
@Entity
@Table(name = "incoming_refund_requests")
public class IncomingRefundRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "refund_id", nullable = false)
    private String refundId;

    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IncomingRefundRequestStatus status;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    public IncomingRefundRequest() {}

    private IncomingRefundRequest(Builder b) {
        this.id = b.id;
        this.paymentId = b.paymentId;
        this.refundId = b.refundId;
        this.amount = b.amount;
        this.currency = b.currency;
        this.reason = b.reason;
        this.status = b.status;
        this.receivedAt = b.receivedAt;
    }

    @PrePersist
    void onCreate() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
        if (status == null) {
            status = IncomingRefundRequestStatus.PENDING;
        }
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Long paymentId;
        private String refundId;
        private BigDecimal amount;
        private String currency;
        private String reason;
        private IncomingRefundRequestStatus status;
        private Instant receivedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder paymentId(Long v) { this.paymentId = v; return this; }
        public Builder refundId(String v) { this.refundId = v; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder status(IncomingRefundRequestStatus v) { this.status = v; return this; }
        public Builder receivedAt(Instant v) { this.receivedAt = v; return this; }
        public IncomingRefundRequest build() { return new IncomingRefundRequest(this); }
    }

    public Long getId() { return id; }
    public Long getPaymentId() { return paymentId; }
    public String getRefundId() { return refundId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public IncomingRefundRequestStatus getStatus() { return status; }
    public Instant getReceivedAt() { return receivedAt; }

    public void setPaymentId(Long v) { this.paymentId = v; }
    public void setRefundId(String v) { this.refundId = v; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setReason(String v) { this.reason = v; }
    public void setStatus(IncomingRefundRequestStatus v) { this.status = v; }
    public void setReceivedAt(Instant v) { this.receivedAt = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof IncomingRefundRequest r)) return false;
        return Objects.equals(id, r.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
