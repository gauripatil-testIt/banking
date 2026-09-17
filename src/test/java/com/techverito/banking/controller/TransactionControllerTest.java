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
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({TransactionController.class, GlobalExceptionHandler.class})
class TransactionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TransactionService transactionService;

    private TransactionResponse response(Long id, TransactionType type, TransactionStatus status) {
        return new TransactionResponse(id, 1L, type, BigDecimal.valueOf(100), BigDecimal.valueOf(900), status);
    }

    @Test
    void GET_transactions_noParams_returnsAll() throws Exception {
        when(transactionService.list(null, null, null)).thenReturn(List.of(
                response(1L, TransactionType.DEPOSIT, TransactionStatus.COMPLETED),
                response(2L, TransactionType.WITHDRAWAL, TransactionStatus.PENDING)
        ));

        mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void GET_transactions_withStatusFilter_returnsFiltered() throws Exception {
        when(transactionService.list(TransactionStatus.COMPLETED, null, null)).thenReturn(List.of(
                response(1L, TransactionType.DEPOSIT, TransactionStatus.COMPLETED)
        ));

        mockMvc.perform(get("/transactions").param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("COMPLETED"));
    }

    @Test
    void GET_transactions_withTypeAndCustomerIdFilter_combinesAsAnd() throws Exception {
        when(transactionService.list(null, TransactionType.DEPOSIT, 1L)).thenReturn(List.of(
                response(1L, TransactionType.DEPOSIT, TransactionStatus.COMPLETED)
        ));

        mockMvc.perform(get("/transactions").param("type", "DEPOSIT").param("customerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].type").value("DEPOSIT"));
    }

    @Test
    void GET_transactions_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get("/transactions").param("status", "bogus"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_transactions_invalidType_returns400() throws Exception {
        mockMvc.perform(get("/transactions").param("type", "bogus"))
                .andExpect(status().isBadRequest());
    }
}
