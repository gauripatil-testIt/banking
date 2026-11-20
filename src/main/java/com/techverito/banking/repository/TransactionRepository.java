package com.techverito.banking.repository;

import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * Every transaction matching the given filters, with its owning customer loaded in the same
     * query so a listing does not issue one query per row. Each filter is optional: a null
     * parameter is ignored, which is what lets one query serve any combination of them.
     */
    @Query("""
            select t from transactions t
            join fetch t.customer
            where (:status is null or t.status = :status)
              and (:type is null or t.type = :type)
              and (:customerId is null or t.customer.id = :customerId)
            """)
    List<Transaction> findFiltered(@Param("status") TransactionStatus status,
                                   @Param("type") TransactionType type,
                                   @Param("customerId") Long customerId);
}
