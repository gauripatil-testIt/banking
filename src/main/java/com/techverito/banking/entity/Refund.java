package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(name = "refunds", uniqueConstraints = @UniqueConstraint(columnNames = {"payment_id", "refund_id"}))
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Column(name = "refund_id", nullable = false)
    private String refundId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RefundStatus status;

    private String reason;

    public Refund() {}

    private Refund(Builder b) {
        this.id = b.id;
        this.payment = b.payment;
        this.refundId = b.refundId;
        this.amount = b.amount;
        this.currency = b.currency;
        this.status = b.status;
        this.reason = b.reason;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Payment payment;
        private String refundId;
        private BigDecimal amount;
        private String currency;
        private RefundStatus status;
        private String reason;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder payment(Payment v) { this.payment = v; return this; }
        public Builder refundId(String v) { this.refundId = v; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder status(RefundStatus v) { this.status = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Refund build() { return new Refund(this); }
    }

    public Long getId() { return id; }
    public Payment getPayment() { return payment; }
    public String getRefundId() { return refundId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public RefundStatus getStatus() { return status; }
    public String getReason() { return reason; }

    public void setPayment(Payment v) { this.payment = v; }
    public void setRefundId(String v) { this.refundId = v; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setStatus(RefundStatus v) { this.status = v; }
    public void setReason(String v) { this.reason = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Refund r)) return false;
        return Objects.equals(id, r.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
