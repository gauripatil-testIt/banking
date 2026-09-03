package com.techverito.banking.repository;

import com.techverito.banking.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Page<Transaction> findByAccount_IdOrderByCreatedAtDesc(Long accountId, Pageable pageable);
}
