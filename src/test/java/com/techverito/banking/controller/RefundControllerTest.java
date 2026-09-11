package com.techverito.banking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.RefundRequest;
import com.techverito.banking.dto.RefundResponse;
import com.techverito.banking.entity.RefundStatus;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.OverRefundException;
import com.techverito.banking.exception.PaymentNotRefundableException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.RefundRepository;
import com.techverito.banking.service.RefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({RefundController.class, GlobalExceptionHandler.class})
class RefundControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    RefundService refundService;

    @MockBean
    RefundRepository refundRepository;

    @Autowired
    ObjectMapper objectMapper;

    private RefundRequest validRequest() {
        return new RefundRequest(BigDecimal.valueOf(75), "USD", "customer request");
    }

    private RefundResponse response(Long id, Long paymentId, String refundId, BigDecimal amount) {
        return new RefundResponse(id, paymentId, refundId, amount, "USD", RefundStatus.PROCESSED, "customer request");
    }

    @Test
    void POST_payments_paymentId_refunds_newRefund_returns201() throws Exception {
        when(refundRepository.findByPayment_IdAndRefundId(1L, "refund-1")).thenReturn(Optional.empty());
        when(refundService.refund(eq(1L), eq("refund-1"), any())).thenReturn(response(1L, 1L, "refund-1", BigDecimal.valueOf(75)));

        mockMvc.perform(post("/payments/1/refunds")
                        .header("X-Refund-Id", "refund-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.paymentId").value(1))
                .andExpect(jsonPath("$.refundId").value("refund-1"))
                .andExpect(jsonPath("$.amount").value(75));
    }

    @Test
    void POST_payments_paymentId_refunds_repeatedRefundId_returns200_unchanged() throws Exception {
        RefundResponse existing = response(1L, 1L, "refund-1", BigDecimal.valueOf(75));
        when(refundRepository.findByPayment_IdAndRefundId(1L, "refund-1")).thenReturn(Optional.of(mock(com.techverito.banking.entity.Refund.class)));
        when(refundService.refund(eq(1L), eq("refund-1"), any())).thenReturn(existing);

        mockMvc.perform(post("/payments/1/refunds")
                        .header("X-Refund-Id", "refund-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.refundId").value("refund-1"))
                .andExpect(jsonPath("$.amount").value(75));

        verify(refundService, times(1)).refund(eq(1L), eq("refund-1"), any());
    }

    @Test
    void POST_payments_paymentId_refunds_overRefund_returns409() throws Exception {
        when(refundRepository.findByPayment_IdAndRefundId(1L, "refund-2")).thenReturn(Optional.empty());
        when(refundService.refund(eq(1L), eq("refund-2"), any()))
                .thenThrow(new OverRefundException("Refund amount exceeds remaining refundable amount"));

        mockMvc.perform(post("/payments/1/refunds")
                        .header("X-Refund-Id", "refund-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isConflict());
    }

    @Test
    void POST_payments_paymentId_refunds_notRefundableState_returns400() throws Exception {
        when(refundRepository.findByPayment_IdAndRefundId(1L, "refund-3")).thenReturn(Optional.empty());
        when(refundService.refund(eq(1L), eq("refund-3"), any()))
                .thenThrow(new PaymentNotRefundableException("Payment 1 is not in a refundable state: REFUNDED"));

        mockMvc.perform(post("/payments/1/refunds")
                        .header("X-Refund-Id", "refund-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_payments_paymentId_refunds_invalidAmount_returns400() throws Exception {
        when(refundRepository.findByPayment_IdAndRefundId(1L, "refund-4")).thenReturn(Optional.empty());
        when(refundService.refund(eq(1L), eq("refund-4"), any()))
                .thenThrow(new IllegalArgumentException("Refund amount must be greater than zero"));

        mockMvc.perform(post("/payments/1/refunds")
                        .header("X-Refund-Id", "refund-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefundRequest(BigDecimal.ZERO, "USD", null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_payments_paymentId_refunds_unknownPayment_returns404() throws Exception {
        when(refundRepository.findByPayment_IdAndRefundId(99L, "refund-5")).thenReturn(Optional.empty());
        when(refundService.refund(eq(99L), eq("refund-5"), any()))
                .thenThrow(new ResourceNotFoundException("Payment", 99L));

        mockMvc.perform(post("/payments/99/refunds")
                        .header("X-Refund-Id", "refund-5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isNotFound());
    }
}
