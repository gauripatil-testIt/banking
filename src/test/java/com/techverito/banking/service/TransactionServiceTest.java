package com.techverito.banking.service;

import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final Instant OCCURRED_AT = Instant.parse("2025-03-04T09:07:45Z");

    @Mock
    TransactionRepository transactionRepository;

    @InjectMocks
    TransactionService transactionService;

    private Customer customer(Long id) {
        return Customer.builder()
                .id(id).firstName("John").lastName("Doe")
                .email("john@example.com").phone("123")
                .status(CustomerStatus.ACTIVE)
                .createdAt(OCCURRED_AT)
                .build();
    }

    private Transaction transaction(Long id, Long customerId, TransactionStatus status,
                                    TransactionType type, String amount) {
        return Transaction.builder()
                .id(id)
                .customer(customer(customerId))
                .status(status)
                .type(type)
                .amount(new BigDecimal(amount))
                .occurredAt(OCCURRED_AT)
                .build();
    }

    @Test
    void list_noFilter_returnsAll() {
        when(transactionRepository.findFiltered(null, null, null))
                .thenReturn(List.of(
                        transaction(1L, 10L, TransactionStatus.COMPLETED, TransactionType.DEPOSIT, "100.00"),
                        transaction(2L, 11L, TransactionStatus.PENDING, TransactionType.WITHDRAWAL, "50.00")));

        List<TransactionResponse> responses = transactionService.list(null, null, null);

        assertThat(responses).hasSize(2);
        assertThat(responses).extracting(TransactionResponse::id).containsExactly(1L, 2L);
        assertThat(responses).extracting(TransactionResponse::customerId).containsExactly(10L, 11L);
        assertThat(responses).extracting(TransactionResponse::type)
                .containsExactly(TransactionType.DEPOSIT, TransactionType.WITHDRAWAL);
        assertThat(responses).extracting(TransactionResponse::status)
                .containsExactly(TransactionStatus.COMPLETED, TransactionStatus.PENDING);
        verify(transactionRepository).findFiltered(null, null, null);
    }

    @Test
    void list_allFilters_delegatesAndMaps() {
        when(transactionRepository.findFiltered(TransactionStatus.COMPLETED, TransactionType.TRANSFER, 7L))
                .thenReturn(List.of(
                        transaction(9L, 7L, TransactionStatus.COMPLETED, TransactionType.TRANSFER, "25.50")));

        List<TransactionResponse> responses =
                transactionService.list(TransactionStatus.COMPLETED, TransactionType.TRANSFER, 7L);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).id()).isEqualTo(9L);
        assertThat(responses.get(0).status()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(responses.get(0).type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(responses.get(0).amount()).isEqualByComparingTo(new BigDecimal("25.50"));
        assertThat(responses.get(0).occurredAt()).isEqualTo(OCCURRED_AT);
        verify(transactionRepository).findFiltered(TransactionStatus.COMPLETED, TransactionType.TRANSFER, 7L);
    }

    @Test
    void list_mapsCustomerIdFromCustomer() {
        when(transactionRepository.findFiltered(null, null, null))
                .thenReturn(List.of(
                        transaction(4L, 42L, TransactionStatus.FAILED, TransactionType.TRANSFER, "12.34")));

        List<TransactionResponse> responses = transactionService.list(null, null, null);

        assertThat(responses).hasSize(1);
        assertThat(responses.get(0).customerId()).isEqualTo(42L);
        assertThat(responses.get(0).id()).isEqualTo(4L);
    }
}
