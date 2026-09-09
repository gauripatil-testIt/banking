package com.techverito.banking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.dto.CustomerResponse;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({CustomerController.class, GlobalExceptionHandler.class})
class CustomerControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    CustomerService customerService;

    @Autowired
    ObjectMapper objectMapper;

    private CustomerRequest validRequest() {
        return new CustomerRequest("John", "Doe", "john@example.com", "123", CustomerStatus.ACTIVE,
                "ID12345", "PASSPORT", LocalDate.of(1990, 1, 1));
    }

    private CustomerResponse response(Long id) {
        return new CustomerResponse(id, "John", "Doe", "john@example.com", "123", CustomerStatus.ACTIVE,
                "ID12345", "PASSPORT", LocalDate.of(1990, 1, 1));
    }

    @Test
    void POST_customers_returns201() throws Exception {
        when(customerService.create(any())).thenReturn(response(1L));

        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.idNumber").value("ID12345"))
                .andExpect(jsonPath("$.idType").value("PASSPORT"))
                .andExpect(jsonPath("$.dateOfBirth").value("1990-01-01"));
    }

    @Test
    void POST_customers_invalidBody_returns400() throws Exception {
        CustomerRequest invalid = new CustomerRequest("", "", "not-an-email", null, null, null, null, null);

        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_customers_invalidKyc_returns400() throws Exception {
        CustomerRequest invalidKyc = new CustomerRequest("John", "Doe", "john@example.com", "123", CustomerStatus.ACTIVE,
                "", "PASSPORT", LocalDate.of(1990, 1, 1));

        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidKyc)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_customers_missingIdNumber_returns400() throws Exception {
        CustomerRequest invalidKyc = new CustomerRequest("John", "Doe", "john@example.com", "123", CustomerStatus.ACTIVE,
                null, "PASSPORT", LocalDate.of(1990, 1, 1));

        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidKyc)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_customers_missingIdType_returns400() throws Exception {
        CustomerRequest invalidKyc = new CustomerRequest("John", "Doe", "john@example.com", "123", CustomerStatus.ACTIVE,
                "ID12345", null, LocalDate.of(1990, 1, 1));

        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidKyc)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_customers_missingDateOfBirth_returns400() throws Exception {
        CustomerRequest invalidKyc = new CustomerRequest("John", "Doe", "john@example.com", "123", CustomerStatus.ACTIVE,
                "ID12345", "PASSPORT", null);

        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidKyc)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_customers_invalidIdType_returns400() throws Exception {
        CustomerRequest invalidKyc = new CustomerRequest("John", "Doe", "john@example.com", "123", CustomerStatus.ACTIVE,
                "ID12345", "FOREIGN_PASSPORT", LocalDate.of(1990, 1, 1));

        mockMvc.perform(post("/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidKyc)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void GET_customers_id_found_returns200() throws Exception {
        when(customerService.getById(1L)).thenReturn(response(1L));

        mockMvc.perform(get("/customers/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.email").value("john@example.com"))
                .andExpect(jsonPath("$.dateOfBirth").value("1990-01-01"));
    }

    @Test
    void GET_customers_id_notFound_returns404() throws Exception {
        when(customerService.getById(99L)).thenThrow(new ResourceNotFoundException("Customer", 99L));

        mockMvc.perform(get("/customers/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void GET_customers_returnsList() throws Exception {
        when(customerService.getAll()).thenReturn(List.of(response(1L), response(2L)));

        mockMvc.perform(get("/customers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void PUT_customers_id_returns200() throws Exception {
        when(customerService.update(eq(1L), any())).thenReturn(response(1L));

        mockMvc.perform(put("/customers/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.idType").value("PASSPORT"));
    }

    @Test
    void PUT_customers_id_notFound_returns404() throws Exception {
        when(customerService.update(eq(99L), any()))
                .thenThrow(new ResourceNotFoundException("Customer", 99L));

        mockMvc.perform(put("/customers/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isNotFound());
    }

    @Test
    void DELETE_customers_id_returns204() throws Exception {
        doNothing().when(customerService).delete(1L);

        mockMvc.perform(delete("/customers/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void DELETE_customers_id_notFound_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("Customer", 99L)).when(customerService).delete(99L);

        mockMvc.perform(delete("/customers/99"))
                .andExpect(status().isNotFound());
    }
}
