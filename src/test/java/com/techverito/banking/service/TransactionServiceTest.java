package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.repository.TransactionRepository;
import com.techverito.banking.repository.TransactionSpecifications;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @InjectMocks
    private TransactionService transactionService;

    private Customer customer(Long id) {
        return Customer.builder()
                .id(id).firstName("John").lastName("Doe")
                .email("john@example.com").phone("123")
                .status(com.techverito.banking.entity.CustomerStatus.ACTIVE).build();
    }

    private Account account(Long id, Long customerId) {
        return Account.builder()
                .id(id)
                .customer(customer(customerId))
                .build();
    }

    private Transaction transaction(Long id, Long accountId, TransactionType type, TransactionStatus status) {
        return Transaction.builder()
                .id(id)
                .account(Account.builder().id(accountId).build())
                .type(type)
                .amount(java.math.BigDecimal.TEN)
                .balanceAfter(java.math.BigDecimal.TEN)
                .status(status)
                .build();
    }

    @Test
    void list_shouldReturnAllTransactions_whenAllFiltersAreNull() {
        Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));

        Transaction t1 = transaction(1L, 1L, TransactionType.DEPOSIT, TransactionStatus.COMPLETED);
        Transaction t2 = transaction(2L, 2L, TransactionType.WITHDRAWAL, TransactionStatus.PENDING);

        Page<Transaction> page = new PageImpl<>(List.of(t1, t2), pageable, 2);
        when(transactionRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        Page<TransactionResponse> result = transactionService.list(null, null, null, pageable);

        assertEquals(2, result.getContent().size());
        verify(transactionRepository).findAll(any(Specification.class), eq(pageable));
    }

    @Test
    void list_shouldApplySingleFilter_status() {
        Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);
        TransactionStatus status = TransactionStatus.COMPLETED;

        Transaction t1 = transaction(1L, 1L, TransactionType.DEPOSIT, status);
        Page<Transaction> page = new PageImpl<>(List.of(t1), pageable, 1);
        when(transactionRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        transactionService.list(status, null, null, pageable);

        ArgumentCaptor<Specification<Transaction>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(transactionRepository).findAll(specCaptor.capture(), eq(pageable));

        Specification<Transaction> expected = TransactionSpecifications.filter(status, null, null);
        assertEquals(expected, specCaptor.getValue());
    }

    @Test
    void list_shouldApplyAllFilters_statusTypeCustomerId() {
        Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 20);

        TransactionStatus status = TransactionStatus.COMPLETED;
        TransactionType type = TransactionType.DEPOSIT;
        Long customerId = 1L;

        Transaction t1 = transaction(1L, 1L, type, status);
        Page<Transaction> page = new PageImpl<>(List.of(t1), pageable, 1);
        when(transactionRepository.findAll(any(Specification.class), eq(pageable))).thenReturn(page);

        transactionService.list(status, type, customerId, pageable);

        ArgumentCaptor<Specification<Transaction>> specCaptor = ArgumentCaptor.forClass(Specification.class);
        verify(transactionRepository).findAll(specCaptor.capture(), eq(pageable));

        Specification<Transaction> expected = TransactionSpecifications.filter(status, type, customerId);
        assertEquals(expected, specCaptor.getValue());
    }
}
