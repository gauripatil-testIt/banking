package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.entity.Transaction;
import com.techverito.banking.repository.TransactionRepository;
import com.techverito.banking.repository.TransactionSpecifications;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public TransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> list(TransactionStatus status, TransactionType type, Long customerId, Pageable pageable) {
        Specification<Transaction> spec = TransactionSpecifications.filter(status, type, customerId);
        return transactionRepository.findAll(spec, pageable).map(TransactionResponse::from);
    }
}
