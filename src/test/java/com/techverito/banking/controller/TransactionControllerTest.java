package com.techverito.banking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionRequestType;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.dto.TransactionStatusUpdateRequest;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.InvalidTransactionStateException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
        return new TransactionResponse(id, 1L, type, BigDecimal.valueOf(100), BigDecimal.valueOf(900),
                status, null, Instant.now());
    }

    @Test
    void POST_transactions_deposit_returns201() throws Exception {
        TransactionRequest request = new TransactionRequest(TransactionRequestType.DEPOSIT, BigDecimal.valueOf(100), null);
        when(transactionService.create(eq(1L), any())).thenReturn(response(1L, TransactionType.DEPOSIT, TransactionStatus.COMPLETED));

        mockMvc.perform(post("/accounts/1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void POST_transactions_withdrawal_returns201() throws Exception {
        TransactionRequest request = new TransactionRequest(TransactionRequestType.WITHDRAWAL, BigDecimal.valueOf(100), null);
        when(transactionService.create(eq(1L), any())).thenReturn(response(1L, TransactionType.WITHDRAWAL, TransactionStatus.REJECTED));

        mockMvc.perform(post("/accounts/1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void POST_transactions_transfer_returns201() throws Exception {
        TransactionRequest request = new TransactionRequest(TransactionRequestType.TRANSFER, BigDecimal.valueOf(100), 2L);
        TransactionResponse res = new TransactionResponse(1L, 1L, TransactionType.WITHDRAWAL, BigDecimal.valueOf(100),
                BigDecimal.valueOf(900), TransactionStatus.COMPLETED, UUID.randomUUID(), Instant.now());
        when(transactionService.create(eq(1L), any())).thenReturn(res);

        mockMvc.perform(post("/accounts/1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").exists());
    }

    @Test
    void POST_transactions_invalidBody_returns400() throws Exception {
        TransactionRequest invalid = new TransactionRequest(null, null, null);

        mockMvc.perform(post("/accounts/1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_transactions_id_found_returns200() throws Exception {
        when(transactionService.getById(1L, 10L)).thenReturn(response(10L, TransactionType.DEPOSIT, TransactionStatus.COMPLETED));

        mockMvc.perform(get("/accounts/1/transactions/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10));
    }

    @Test
    void GET_transactions_id_notFound_returns404() throws Exception {
        when(transactionService.getById(1L, 99L)).thenThrow(new ResourceNotFoundException("Transaction", 99L));

        mockMvc.perform(get("/accounts/1/transactions/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void GET_transactions_list_returnsPagedBody() throws Exception {
        List<TransactionResponse> content = List.of(
                response(1L, TransactionType.WITHDRAWAL, TransactionStatus.REJECTED)
        );
        when(transactionService.list(eq(1L), eq(TransactionStatus.REJECTED), any()))
                .thenReturn(new PageImpl<>(content, PageRequest.of(0, 10), 1));

        mockMvc.perform(get("/accounts/1/transactions")
                        .param("status", "REJECTED")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("REJECTED"));
    }

    @Test
    void PUT_transactions_id_cancelCompleted_returns200() throws Exception {
        when(transactionService.updateStatus(eq(1L), eq(10L), any()))
                .thenReturn(response(10L, TransactionType.WITHDRAWAL, TransactionStatus.CANCELED));

        mockMvc.perform(put("/accounts/1/transactions/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionStatusUpdateRequest(TransactionStatus.CANCELED))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELED"));
    }

    @Test
    void PUT_transactions_id_nonCompleted_returns409() throws Exception {
        when(transactionService.updateStatus(eq(1L), eq(10L), any()))
                .thenThrow(new InvalidTransactionStateException("Transaction 10 cannot transition"));

        mockMvc.perform(put("/accounts/1/transactions/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionStatusUpdateRequest(TransactionStatus.CANCELED))))
                .andExpect(status().isConflict());
    }

    @Test
    void PUT_transactions_id_notFound_returns404() throws Exception {
        when(transactionService.updateStatus(eq(1L), eq(99L), any()))
                .thenThrow(new ResourceNotFoundException("Transaction", 99L));

        mockMvc.perform(put("/accounts/1/transactions/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TransactionStatusUpdateRequest(TransactionStatus.CANCELED))))
                .andExpect(status().isNotFound());
    }

    @Test
    void DELETE_transactions_id_rejected_returns204() throws Exception {
        doNothing().when(transactionService).delete(1L, 10L);

        mockMvc.perform(delete("/accounts/1/transactions/10"))
                .andExpect(status().isNoContent());
    }

    @Test
    void DELETE_transactions_id_completed_returns409() throws Exception {
        doThrow(new InvalidTransactionStateException("Transaction 10 cannot be deleted"))
                .when(transactionService).delete(1L, 10L);

        mockMvc.perform(delete("/accounts/1/transactions/10"))
                .andExpect(status().isConflict());
    }

    @Test
    void DELETE_transactions_id_notFound_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Transaction", 99L))
                .when(transactionService).delete(1L, 99L);

        mockMvc.perform(delete("/accounts/1/transactions/99"))
                .andExpect(status().isNotFound());
    }
}
