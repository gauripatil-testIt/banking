package com.techverito.banking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.PagedResponse;
import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.dto.TransferRequest;
import com.techverito.banking.dto.TransferResponse;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.InsufficientFundsException;
import com.techverito.banking.exception.InvalidTransactionStateException;
import com.techverito.banking.exception.InvalidTransactionTypeException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({TransactionController.class, GlobalExceptionHandler.class})
class TransactionControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TransactionService transactionService;

    @Autowired
    ObjectMapper objectMapper;

    private TransactionResponse response(Long id, TransactionType type, TransactionStatus status) {
        return new TransactionResponse(id, 1L, type, BigDecimal.valueOf(200), BigDecimal.valueOf(800), status, null, Instant.now());
    }

    @Test
    void POST_accounts_id_transactions_deposit_returns201() throws Exception {
        when(transactionService.recordTransaction(eq(1L), any()))
                .thenReturn(response(1L, TransactionType.DEPOSIT, TransactionStatus.COMPLETED));

        mockMvc.perform(post("/accounts/1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionRequest(TransactionType.DEPOSIT, BigDecimal.valueOf(200)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void POST_accounts_id_transactions_invalidBody_returns400() throws Exception {
        TransactionRequest invalid = new TransactionRequest(null, BigDecimal.valueOf(-5));

        mockMvc.perform(post("/accounts/1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_accounts_id_transactions_transferType_returns400() throws Exception {
        when(transactionService.recordTransaction(eq(1L), any()))
                .thenThrow(new InvalidTransactionTypeException("Transfers must be submitted via POST /transfers"));

        mockMvc.perform(post("/accounts/1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionRequest(TransactionType.TRANSFER, BigDecimal.valueOf(200)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_accounts_id_transactions_returnsPagedResponse() throws Exception {
        PagedResponse<TransactionResponse> paged = new PagedResponse<>(
                java.util.List.of(response(1L, TransactionType.DEPOSIT, TransactionStatus.COMPLETED)), 0, 20, 1, 1);
        when(transactionService.history(eq(1L), eq(TransactionStatus.COMPLETED), eq(0), eq(20))).thenReturn(paged);

        mockMvc.perform(get("/accounts/1/transactions")
                        .param("status", "COMPLETED")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.pageNumber").value(0))
                .andExpect(jsonPath("$.pageSize").value(20))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    void POST_transfers_returns201() throws Exception {
        TransferResponse res = new TransferResponse(
                response(1L, TransactionType.WITHDRAWAL, TransactionStatus.COMPLETED),
                response(2L, TransactionType.DEPOSIT, TransactionStatus.COMPLETED));
        when(transactionService.transfer(any())).thenReturn(res);

        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest(1L, 2L, BigDecimal.valueOf(200)))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.withdrawal.type").value("WITHDRAWAL"))
                .andExpect(jsonPath("$.deposit.type").value("DEPOSIT"));
    }

    @Test
    void POST_transfers_insufficientFunds_returns400() throws Exception {
        when(transactionService.transfer(any())).thenThrow(new InsufficientFundsException(1L));

        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest(1L, 2L, BigDecimal.valueOf(200)))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_transfers_missingAccount_returns404() throws Exception {
        when(transactionService.transfer(any())).thenThrow(new ResourceNotFoundException("Account", 1L));

        mockMvc.perform(post("/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransferRequest(1L, 2L, BigDecimal.valueOf(200)))))
                .andExpect(status().isNotFound());
    }

    @Test
    void POST_transactions_id_cancel_returns200() throws Exception {
        when(transactionService.cancel(1L)).thenReturn(response(1L, TransactionType.WITHDRAWAL, TransactionStatus.CANCELLED));

        mockMvc.perform(post("/transactions/1/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void DELETE_transactions_id_rejected_returns204() throws Exception {
        doNothing().when(transactionService).delete(1L);

        mockMvc.perform(delete("/transactions/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void DELETE_transactions_id_completed_returns409() throws Exception {
        doThrow(new InvalidTransactionStateException(
                "Only rejected transactions can be deleted; cancel completed or cancelled transactions instead"))
                .when(transactionService).delete(1L);

        mockMvc.perform(delete("/transactions/1"))
                .andExpect(status().isConflict());
    }
}
