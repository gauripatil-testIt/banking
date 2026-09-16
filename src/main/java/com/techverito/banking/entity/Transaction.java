package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private BigDecimal balanceAfter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionStatus status;

    public Transaction() {}

    private Transaction(Builder b) {
        this.id = b.id;
        this.account = b.account;
        this.type = b.type;
        this.amount = b.amount;
        this.balanceAfter = b.balanceAfter;
        this.status = b.status;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Account account;
        private TransactionType type;
        private BigDecimal amount;
        private BigDecimal balanceAfter;
        private TransactionStatus status;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder account(Account v) { this.account = v; return this; }
        public Builder type(TransactionType v) { this.type = v; return this; }
        public Builder amount(BigDecimal v) { this.amount = v; return this; }
        public Builder balanceAfter(BigDecimal v) { this.balanceAfter = v; return this; }
        public Builder status(TransactionStatus v) { this.status = v; return this; }
        public Transaction build() { return new Transaction(this); }
    }

    public Long getId() { return id; }
    public Account getAccount() { return account; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public TransactionStatus getStatus() { return status; }

    public void setAccount(Account v) { this.account = v; }
    public void setType(TransactionType v) { this.type = v; }
    public void setAmount(BigDecimal v) { this.amount = v; }
    public void setBalanceAfter(BigDecimal v) { this.balanceAfter = v; }
    public void setStatus(TransactionStatus v) { this.status = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Transaction t)) return false;
        return Objects.equals(id, t.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
