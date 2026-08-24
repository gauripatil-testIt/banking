package com.techverito.banking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.AccountRequest;
import com.techverito.banking.dto.AccountResponse;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({AccountController.class, GlobalExceptionHandler.class})
class AccountControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AccountService accountService;

    @Autowired
    ObjectMapper objectMapper;

    private AccountRequest validRequest() {
        return new AccountRequest(1L, "ACC001", AccountType.SAVINGS, BigDecimal.valueOf(1000), AccountStatus.ACTIVE, "USD");
    }

    private AccountResponse response(Long id) {
        return new AccountResponse(id, 1L, "ACC001", AccountType.SAVINGS, BigDecimal.valueOf(1000), AccountStatus.ACTIVE, "USD");
    }

    @Test
    void POST_accounts_returns201() throws Exception {
        when(accountService.create(any())).thenReturn(response(1L));

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.accountNumber").value("ACC001"))
                .andExpect(jsonPath("$.type").value("SAVINGS"));
    }

    @Test
    void POST_accounts_invalidBody_returns400() throws Exception {
        AccountRequest invalid = new AccountRequest(null, "", null, null, null, "");

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_accounts_missingCurrency_returns400() throws Exception {
        AccountRequest invalid = new AccountRequest(1L, "ACC001", AccountType.SAVINGS, BigDecimal.valueOf(1000), AccountStatus.ACTIVE, " ");

        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_accounts_id_found_returns200() throws Exception {
        when(accountService.getById(1L)).thenReturn(response(1L));

        mockMvc.perform(get("/accounts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void GET_accounts_id_notFound_returns404() throws Exception {
        when(accountService.getById(99L)).thenThrow(new ResourceNotFoundException("Account", 99L));

        mockMvc.perform(get("/accounts/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void GET_accounts_noFilter_returnsAll() throws Exception {
        when(accountService.list(null)).thenReturn(List.of(response(1L), response(2L)));

        mockMvc.perform(get("/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void GET_accounts_withCustomerId_returnsFiltered() throws Exception {
        when(accountService.list(1L)).thenReturn(List.of(response(1L)));

        mockMvc.perform(get("/accounts").param("customerId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void PUT_accounts_id_returns200() throws Exception {
        when(accountService.update(eq(1L), any())).thenReturn(response(1L));

        mockMvc.perform(put("/accounts/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void PUT_accounts_id_notFound_returns404() throws Exception {
        when(accountService.update(eq(99L), any()))
                .thenThrow(new ResourceNotFoundException("Account", 99L));

        mockMvc.perform(put("/accounts/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isNotFound());
    }

    @Test
    void DELETE_accounts_id_returns204() throws Exception {
        doNothing().when(accountService).delete(1L);

        mockMvc.perform(delete("/accounts/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void DELETE_accounts_id_notFound_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Account", 99L)).when(accountService).delete(99L);

        mockMvc.perform(delete("/accounts/99"))
                .andExpect(status().isNotFound());
    }
}
