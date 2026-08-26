package com.techverito.banking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.BeneficiaryRequest;
import com.techverito.banking.dto.BeneficiaryResponse;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.service.BeneficiaryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({BeneficiaryController.class, GlobalExceptionHandler.class})
class BeneficiaryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    BeneficiaryService beneficiaryService;

    @Autowired
    ObjectMapper objectMapper;

    private BeneficiaryRequest validRequest() {
        return new BeneficiaryRequest(1L, "Jane Payee", "ACC999", "First Bank", "INDIVIDUAL");
    }

    private BeneficiaryResponse response(Long id) {
        return new BeneficiaryResponse(id, 1L, "Jane Payee", "ACC999", "First Bank", "INDIVIDUAL");
    }

    @Test
    void POST_beneficiaries_returns201() throws Exception {
        when(beneficiaryService.create(any())).thenReturn(response(1L));

        mockMvc.perform(post("/beneficiaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.customerId").value(1))
                .andExpect(jsonPath("$.name").value("Jane Payee"))
                .andExpect(jsonPath("$.accountNumber").value("ACC999"))
                .andExpect(jsonPath("$.bankName").value("First Bank"))
                .andExpect(jsonPath("$.beneficiaryType").value("INDIVIDUAL"));
    }

    @Test
    void POST_beneficiaries_invalidBody_returns400() throws Exception {
        BeneficiaryRequest invalid = new BeneficiaryRequest(null, "", "", "", "");

        mockMvc.perform(post("/beneficiaries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_beneficiaries_id_found_returns200() throws Exception {
        when(beneficiaryService.getById(1L)).thenReturn(response(1L));

        mockMvc.perform(get("/beneficiaries/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(1))
                .andExpect(jsonPath("$.bankName").value("First Bank"));
    }

    @Test
    void GET_beneficiaries_id_notFound_returns404() throws Exception {
        when(beneficiaryService.getById(99L)).thenThrow(new ResourceNotFoundException("Beneficiary", 99L));

        mockMvc.perform(get("/beneficiaries/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void GET_customers_customerId_beneficiaries_returnsList() throws Exception {
        when(beneficiaryService.list(1L)).thenReturn(List.of(response(1L), response(2L)));

        mockMvc.perform(get("/customers/1/beneficiaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void GET_customers_customerId_beneficiaries_customerNotFound_returns404() throws Exception {
        when(beneficiaryService.list(99L)).thenThrow(new ResourceNotFoundException("Customer", 99L));

        mockMvc.perform(get("/customers/99/beneficiaries"))
                .andExpect(status().isNotFound());
    }

    @Test
    void PUT_beneficiaries_id_returns200() throws Exception {
        when(beneficiaryService.update(eq(1L), any())).thenReturn(response(1L));

        mockMvc.perform(put("/beneficiaries/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void PUT_beneficiaries_id_notFound_returns404() throws Exception {
        when(beneficiaryService.update(eq(99L), any()))
                .thenThrow(new ResourceNotFoundException("Beneficiary", 99L));

        mockMvc.perform(put("/beneficiaries/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isNotFound());
    }

    @Test
    void DELETE_beneficiaries_id_returns204() throws Exception {
        doNothing().when(beneficiaryService).delete(1L);

        mockMvc.perform(delete("/beneficiaries/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void DELETE_beneficiaries_id_notFound_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Beneficiary", 99L)).when(beneficiaryService).delete(99L);

        mockMvc.perform(delete("/beneficiaries/99"))
                .andExpect(status().isNotFound());
    }
}
