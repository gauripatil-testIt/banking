package com.techverito.banking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    private TransactionRequest validRequest() {
        return new TransactionRequest(1L, TransactionType.DEPOSIT, BigDecimal.valueOf(100), BigDecimal.valueOf(1100), TransactionStatus.PENDING, null);
    }

    private TransactionResponse response(Long id) {
        return new TransactionResponse(id, 1L, TransactionType.DEPOSIT, BigDecimal.valueOf(100), BigDecimal.valueOf(1100), TransactionStatus.PENDING, null, LocalDateTime.now());
    }

    @Test
    void POST_transactions_returns201() throws Exception {
        when(transactionService.create(any())).thenReturn(response(1L));

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.accountId").value(1))
                .andExpect(jsonPath("$.type").value("DEPOSIT"));
    }

    @Test
    void POST_transactions_invalidBody_returns400() throws Exception {
        TransactionRequest invalid = new TransactionRequest(null, null, null, null, null, null);

        mockMvc.perform(post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_accounts_accountId_transactions_returns201() throws Exception {
        when(transactionService.create(any())).thenReturn(response(1L));

        mockMvc.perform(post("/accounts/1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void POST_accounts_accountId_transactions_invalidBody_returns400() throws Exception {
        TransactionRequest invalid = new TransactionRequest(1L, null, null, null, null, null);

        mockMvc.perform(post("/accounts/1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_transactions_id_found_returns200() throws Exception {
        when(transactionService.getById(1L)).thenReturn(response(1L));

        mockMvc.perform(get("/transactions/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(1))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void GET_transactions_id_notFound_returns404() throws Exception {
        when(transactionService.getById(99L)).thenThrow(new ResourceNotFoundException("Transaction", 99L));

        mockMvc.perform(get("/transactions/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void GET_transactions_noFilter_returnsAll() throws Exception {
        when(transactionService.list(isNull(), isNull(), isNull(), isNull())).thenReturn(List.of(response(1L), response(2L)));

        mockMvc.perform(get("/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void GET_transactions_withAccountId_returnsFiltered() throws Exception {
        when(transactionService.list(eq(1L), isNull(), isNull(), isNull())).thenReturn(List.of(response(1L)));

        mockMvc.perform(get("/transactions").param("accountId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void GET_transactions_withStatus_returnsFiltered() throws Exception {
        when(transactionService.list(isNull(), eq(TransactionStatus.COMPLETED), isNull(), isNull()))
                .thenReturn(List.of(response(1L)));

        mockMvc.perform(get("/transactions").param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void GET_transactions_withType_returnsFiltered() throws Exception {
        when(transactionService.list(isNull(), isNull(), eq(TransactionType.DEPOSIT), isNull()))
                .thenReturn(List.of(response(1L)));

        mockMvc.perform(get("/transactions").param("type", "DEPOSIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void GET_transactions_withCustomerId_returnsFiltered() throws Exception {
        when(transactionService.list(isNull(), isNull(), isNull(), eq(1L)))
                .thenReturn(List.of(response(1L)));

        mockMvc.perform(get("/transactions").param("customerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void GET_transactions_withTypeAndCustomerId_returnsFiltered() throws Exception {
        when(transactionService.list(isNull(), isNull(), eq(TransactionType.DEPOSIT), eq(1L)))
                .thenReturn(List.of(response(1L)));

        mockMvc.perform(get("/transactions").param("type", "DEPOSIT").param("customerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void GET_transactions_filtersYieldNoMatches_returnsEmptyArray() throws Exception {
        when(transactionService.list(eq(999L), eq(TransactionStatus.FAILED), eq(TransactionType.TRANSFER), eq(999L)))
                .thenReturn(List.of());

        mockMvc.perform(get("/transactions")
                        .param("accountId", "999")
                        .param("status", "FAILED")
                        .param("type", "TRANSFER")
                        .param("customerId", "999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void GET_transactions_invalidStatus_returns400() throws Exception {
        mockMvc.perform(get("/transactions").param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_accounts_accountId_transactions_returnsFiltered() throws Exception {
        when(transactionService.list(1L)).thenReturn(List.of(response(1L)));

        mockMvc.perform(get("/accounts/1/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void PUT_transactions_id_returns200() throws Exception {
        when(transactionService.update(eq(1L), any())).thenReturn(response(1L));

        mockMvc.perform(put("/transactions/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void PUT_transactions_id_notFound_returns404() throws Exception {
        when(transactionService.update(eq(99L), any()))
                .thenThrow(new ResourceNotFoundException("Transaction", 99L));

        mockMvc.perform(put("/transactions/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isNotFound());
    }

    @Test
    void DELETE_transactions_id_returns204() throws Exception {
        doNothing().when(transactionService).delete(1L);

        mockMvc.perform(delete("/transactions/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void DELETE_transactions_id_notFound_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Transaction", 99L)).when(transactionService).delete(99L);

        mockMvc.perform(delete("/transactions/99"))
                .andExpect(status().isNotFound());
    }
}
