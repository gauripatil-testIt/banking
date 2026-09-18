package com.techverito.banking.repository;

import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Page<Transaction> findByAccount_Id(Long accountId, Pageable pageable);
    Page<Transaction> findByAccount_IdAndStatus(Long accountId, TransactionStatus status, Pageable pageable);
}
