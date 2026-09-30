package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false, updatable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private TransactionType type;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(nullable = false, updatable = false)
    private BigDecimal resultingBalance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    @Column(updatable = false)
    private String transferGroupId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public Transaction() {}

    private Transaction(Builder b) {
        this.id = b.id;
        this.account = b.account;
        this.type = b.type;
        this.amount = b.amount;
        this.resultingBalance = b.resultingBalance;
        this.status = b.status;
        this.transferGroupId = b.transferGroupId;
        this.createdAt = b.createdAt != null ? b.createdAt : Instant.now();
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Account account;
        private TransactionType type;
        private BigDecimal amount;
        private BigDecimal resultingBalance;
        private TransactionStatus status;
        private String transferGroupId;
        private Instant createdAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder account(Account v) { this.account = v; return this; }
        public Builder type(TransactionType v) { this.type = v; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder resultingBalance(BigDecimal v) { this.resultingBalance = v; return this; }
        public Builder status(TransactionStatus v) { this.status = v; return this; }
        public Builder transferGroupId(String v) { this.transferGroupId = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Transaction build() { return new Transaction(this); }
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getResultingBalance() { return resultingBalance; }
    public TransactionStatus getStatus() { return status; }
    public String getTransferGroupId() { return transferGroupId; }
    public Instant getCreatedAt() { return createdAt; }

    public void markCancelled() {
        if (status != TransactionStatus.COMPLETED) {
            throw new IllegalStateException("Only completed transactions can be cancelled");
        }
        this.status = TransactionStatus.CANCELLED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction t)) return false;
        return Objects.equals(id, t.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
