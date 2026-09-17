package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class TransactionService {

    private final TransactionRepository transactionRepository;

    public TransactionService(TransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> list(TransactionStatus status, TransactionType type, Long customerId) {
        return transactionRepository.findByFilters(status, type, customerId).stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
