package com.techverito.banking.repository;

import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    List<Transaction> findByAccount_Id(Long accountId);

    @Query("SELECT t FROM Transaction t WHERE (:accountId IS NULL OR t.account.id = :accountId) " +
            "AND (:status IS NULL OR t.status = :status) " +
            "AND (:type IS NULL OR t.type = :type) " +
            "AND (:customerId IS NULL OR t.account.customer.id = :customerId)")
    List<Transaction> findByFilters(@Param("accountId") Long accountId,
                                     @Param("status") TransactionStatus status,
                                     @Param("type") TransactionType type,
                                     @Param("customerId") Long customerId);
}
