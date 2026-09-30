package com.techverito.banking.repository;

import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    Page<Transaction> findByAccount_IdOrderByCreatedAtDesc(Long accountId, Pageable pageable);

    Page<Transaction> findByAccount_IdAndStatusOrderByCreatedAtDesc(Long accountId, TransactionStatus status, Pageable pageable);

    List<Transaction> findByTransferGroupIdAndIdNot(String transferGroupId, Long id);
}
