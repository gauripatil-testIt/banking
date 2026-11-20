package com.techverito.banking.controller;

import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({TransactionController.class, GlobalExceptionHandler.class})
class TransactionControllerTest {

    private static final Instant OCCURRED_AT = Instant.parse("2025-03-04T09:07:45Z");

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TransactionService transactionService;

    private TransactionResponse response(Long id) {
        return new TransactionResponse(id, 1L, TransactionType.DEPOSIT, TransactionStatus.COMPLETED,
                new BigDecimal("100.00"), OCCURRED_AT);
    }

    @Test
    void GET_transactions_noFilter_returnsAll() throws Exception {
        when(transactionService.list(null, null, null)).thenReturn(List.of(response(1L), response(2L)));

        mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].customerId").value(1))
                .andExpect(jsonPath("$[0].type").value("DEPOSIT"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$[0].amount").value(100.00))
                .andExpect(jsonPath("$[0].occurredAt").value("2025-03-04T09:07:45Z"));

        verify(transactionService).list(null, null, null);
    }

    @Test
    void GET_transactions_statusTypeCustomerId_forwardsParams() throws Exception {
        when(transactionService.list(TransactionStatus.COMPLETED, TransactionType.TRANSFER, 7L))
                .thenReturn(List.of(new TransactionResponse(9L, 7L, TransactionType.TRANSFER,
                        TransactionStatus.COMPLETED, new BigDecimal("25.50"), OCCURRED_AT)));

        mockMvc.perform(get("/transactions")
                        .param("status", "COMPLETED")
                        .param("type", "TRANSFER")
                        .param("customerId", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(9))
                .andExpect(jsonPath("$[0].customerId").value(7))
                .andExpect(jsonPath("$[0].type").value("TRANSFER"))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));

        verify(transactionService).list(TransactionStatus.COMPLETED, TransactionType.TRANSFER, 7L);
    }

    @Test
    void GET_transactions_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get("/transactions").param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists());

        verify(transactionService, never()).list(any(), any(), any());
    }
}
