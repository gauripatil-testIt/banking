package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.Objects;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false, unique = true)
    private String accountNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    @Column(nullable = false)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountStatus status;

    @Version
    private Long version;

    public Account() {}

    private Account(Builder b) {
        this.id = b.id;
        this.customer = b.customer;
        this.accountNumber = b.accountNumber;
        this.type = b.type;
        this.balance = b.balance;
        this.status = b.status;
        this.version = b.version;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Customer customer;
        private String accountNumber;
        private AccountType type;
        private BigDecimal balance;
        private AccountStatus status;
        private Long version;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder customer(Customer v) { this.customer = v; return this; }
        public Builder accountNumber(String v) { this.accountNumber = v; return this; }
        public Builder type(AccountType v) { this.type = v; return this; }
        public Builder balance(BigDecimal v) { this.balance = v; return this; }
        public Builder status(AccountStatus v) { this.status = v; return this; }
        public Builder version(Long v) { this.version = v; return this; }
        public Account build() { return new Account(this); }
    }

    public Long getId() { return id; }
    public Customer getCustomer() { return customer; }
    public String getAccountNumber() { return accountNumber; }
    public AccountType getType() { return type; }
    public BigDecimal getBalance() { return balance; }
    public AccountStatus getStatus() { return status; }
    public Long getVersion() { return version; }

    public void setCustomer(Customer v) { this.customer = v; }
    public void setAccountNumber(String v) { this.accountNumber = v; }
    public void setType(AccountType v) { this.type = v; }
    public void setBalance(BigDecimal v) { this.balance = v; }
    public void setStatus(AccountStatus v) { this.status = v; }
    public void setVersion(Long v) { this.version = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Account a)) return false;
        return Objects.equals(id, a.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
