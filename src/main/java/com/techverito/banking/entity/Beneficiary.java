package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.util.Objects;

@Entity
@Table(name = "beneficiaries")
public class Beneficiary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String accountNumber;

    @Column(nullable = false)
    private String bankName;

    @Column(nullable = false)
    private String beneficiaryType;

    public Beneficiary() {}

    private Beneficiary(Builder b) {
        this.id = b.id;
        this.customer = b.customer;
        this.name = b.name;
        this.accountNumber = b.accountNumber;
        this.bankName = b.bankName;
        this.beneficiaryType = b.beneficiaryType;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Customer customer;
        private String name;
        private String accountNumber;
        private String bankName;
        private String beneficiaryType;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder customer(Customer v) { this.customer = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder accountNumber(String v) { this.accountNumber = v; return this; }
        public Builder bankName(String v) { this.bankName = v; return this; }
        public Builder beneficiaryType(String v) { this.beneficiaryType = v; return this; }
        public Beneficiary build() { return new Beneficiary(this); }
    }

    public Long getId() { return id; }
    public Customer getCustomer() { return customer; }
    public String getName() { return name; }
    public String getAccountNumber() { return accountNumber; }
    public String getBankName() { return bankName; }
    public String getBeneficiaryType() { return beneficiaryType; }

    public void setCustomer(Customer v) { this.customer = v; }
    public void setName(String v) { this.name = v; }
    public void setAccountNumber(String v) { this.accountNumber = v; }
    public void setBankName(String v) { this.bankName = v; }
    public void setBeneficiaryType(String v) { this.beneficiaryType = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Beneficiary b)) return false;
        return Objects.equals(id, b.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
