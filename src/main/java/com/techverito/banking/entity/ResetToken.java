package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "reset_tokens")
public class ResetToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant usedAt;

    public ResetToken() {}

    private ResetToken(Builder b) {
        this.id = b.id;
        this.token = b.token;
        this.customer = b.customer;
        this.expiresAt = b.expiresAt;
        this.usedAt = b.usedAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private String token;
        private Customer customer;
        private Instant expiresAt;
        private Instant usedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder token(String v) { this.token = v; return this; }
        public Builder customer(Customer v) { this.customer = v; return this; }
        public Builder expiresAt(Instant v) { this.expiresAt = v; return this; }
        public Builder usedAt(Instant v) { this.usedAt = v; return this; }
        public ResetToken build() { return new ResetToken(this); }
    }

    public Long getId() { return id; }
    public String getToken() { return token; }
    public Customer getCustomer() { return customer; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getUsedAt() { return usedAt; }

    public void setToken(String v) { this.token = v; }
    public void setCustomer(Customer v) { this.customer = v; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public void setUsedAt(Instant v) { this.usedAt = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ResetToken r)) return false;
        return Objects.equals(id, r.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
