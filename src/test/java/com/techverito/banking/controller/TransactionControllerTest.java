package com.techverito.banking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(SpringExtension.class)
@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TransactionService transactionService;

    @Test
    void getTransactions_shouldReturnJsonArray_whenNoQueryParamsProvided() throws Exception {
        TransactionResponse r1 = TransactionResponse.from(null);

        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<TransactionResponse> page = new PageImpl<>(List.of(r1), pageable, 1);

        when(transactionService.list(ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), ArgumentMatchers.isNull(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/transactions")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0]").exists());

        verify(transactionService).list(isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void getTransactions_shouldApplyQueryParams() throws Exception {
        TransactionResponse r1 = TransactionResponse.from(null);

        TransactionStatus status = TransactionStatus.COMPLETED;
        TransactionType type = TransactionType.DEPOSIT;
        Long customerId = 1L;

        Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<TransactionResponse> page = new PageImpl<>(List.of(r1), pageable, 1);

        when(transactionService.list(eq(status), eq(type), eq(customerId), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/transactions")
                        .param("status", status.name())
                        .param("type", type.name())
                        .param("customerId", String.valueOf(customerId))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").exists());

        verify(transactionService).list(eq(status), eq(type), eq(customerId), any(Pageable.class));
    }
}
