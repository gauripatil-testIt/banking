package com.techverito.banking.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderLineItem> lineItems = new ArrayList<>();

    @Column(name = "discount_amount", nullable = false, columnDefinition = "DECIMAL(19,2) DEFAULT 0")
    private BigDecimal discountAmount;

    @Column(nullable = false)
    private BigDecimal total;

    public Order() {
    }

    private Order(List<OrderLineItem> lineItems, BigDecimal discountAmount, BigDecimal total) {
        this.lineItems = lineItems;
        this.discountAmount = discountAmount;
        this.total = total;
    }

    public Long getId() {
        return id;
    }

    public List<OrderLineItem> getLineItems() {
        return lineItems;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private List<OrderLineItem> lineItems;
        private BigDecimal discountAmount;
        private BigDecimal total;

        public Builder lineItems(List<OrderLineItem> lineItems) {
            this.lineItems = lineItems;
            return this;
        }

        public Builder discountAmount(BigDecimal discountAmount) {
            this.discountAmount = discountAmount;
            return this;
        }

        public Builder total(BigDecimal total) {
            this.total = total;
            return this;
        }

        public Order build() {
            return new Order(lineItems, discountAmount, total);
        }
    }
}
