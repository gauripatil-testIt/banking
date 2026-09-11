package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    public Payment() {}

    private Payment(Builder b) {
        this.id = b.id;
        this.totalAmount = b.totalAmount;
        this.currency = b.currency;
        this.status = b.status;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private BigDecimal totalAmount;
        private String currency;
        private PaymentStatus status;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder totalAmount(BigDecimal v) { this.totalAmount = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder status(PaymentStatus v) { this.status = v; return this; }
        public Payment build() { return new Payment(this); }
    }

    public Long getId() { return id; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }

    public void setTotalAmount(BigDecimal v) { this.totalAmount = v; }
    public void setCurrency(String v) { this.currency = v; }
    public void setStatus(PaymentStatus v) { this.status = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Payment p)) return false;
        return Objects.equals(id, p.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
