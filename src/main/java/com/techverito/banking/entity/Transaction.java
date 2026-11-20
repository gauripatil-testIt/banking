package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity(name = "transactions")
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    public Transaction() {}

    private Transaction(Builder b) {
        this.id = b.id;
        this.customer = b.customer;
        this.type = b.type;
        this.status = b.status;
        this.amount = b.amount;
        this.occurredAt = b.occurredAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Customer customer;
        private TransactionType type;
        private TransactionStatus status;
        private BigDecimal amount;
        private Instant occurredAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder customer(Customer v) { this.customer = v; return this; }
        public Builder type(TransactionType v) { this.type = v; return this; }
        public Builder status(TransactionStatus v) { this.status = v; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder occurredAt(Instant v) { this.occurredAt = v; return this; }
        public Transaction build() { return new Transaction(this); }
    }

    public Long getId() { return id; }
    public Customer getCustomer() { return customer; }
    public TransactionType getType() { return type; }
    public TransactionStatus getStatus() { return status; }
    public BigDecimal getAmount() { return amount; }
    public Instant getOccurredAt() { return occurredAt; }

    public void setCustomer(Customer v) { this.customer = v; }
    public void setType(TransactionType v) { this.type = v; }
    public void setStatus(TransactionStatus v) { this.status = v; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public void setOccurredAt(Instant v) { this.occurredAt = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction t)) return false;
        return Objects.equals(id, t.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
