package com.techverito.banking.repository;

import com.techverito.banking.entity.*;
import org.springframework.data.jpa.domain.Specification;

public class TransactionSpecifications {

    public static Specification<Transaction> hasStatus(TransactionStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Transaction> hasType(TransactionType type) {
        return type == null ? null : (root, query, cb) -> cb.equal(root.get("type"), type);
    }

    public static Specification<Transaction> hasCustomerId(Long customerId) {
        if (customerId == null) return null;
        return (root, query, cb) -> cb.equal(root.join("account").join("customer").get("id"), customerId);
    }

    public static Specification<Transaction> filter(TransactionStatus status, TransactionType type, Long customerId) {
        return Specification.where(hasStatus(status))
                .and(hasType(type))
                .and(hasCustomerId(customerId));
    }
}
