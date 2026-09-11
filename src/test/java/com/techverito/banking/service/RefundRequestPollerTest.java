package com.techverito.banking.service;

import com.techverito.banking.dto.RefundResponse;
import com.techverito.banking.entity.IncomingRefundRequest;
import com.techverito.banking.entity.IncomingRefundRequestStatus;
import com.techverito.banking.entity.RefundStatus;
import com.techverito.banking.repository.IncomingRefundRequestRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefundRequestPollerTest {

    @Mock
    IncomingRefundRequestRepository incomingRefundRequestRepository;

    @Mock
    RefundService refundService;

    @InjectMocks
    RefundRequestPoller refundRequestPoller;

    private IncomingRefundRequest pending(Long id) {
        return IncomingRefundRequest.builder()
                .id(id)
                .paymentId(1L)
                .refundId("refund-1")
                .amount(BigDecimal.valueOf(75))
                .currency("USD")
                .reason("customer request")
                .status(IncomingRefundRequestStatus.PENDING)
                .build();
    }

    @Test
    void pollAndProcess_pendingRequest_isProcessedExactlyOnceAndMarkedProcessed() {
        IncomingRefundRequest incoming = pending(1L);
        when(incomingRefundRequestRepository.findByStatus(IncomingRefundRequestStatus.PENDING))
                .thenReturn(List.of(incoming));
        when(refundService.refund(eq(1L), eq("refund-1"), any()))
                .thenReturn(new RefundResponse(1L, 1L, "refund-1", BigDecimal.valueOf(75), "USD",
                        RefundStatus.PROCESSED, "customer request"));

        refundRequestPoller.pollAndProcess();

        verify(refundService, times(1)).refund(eq(1L), eq("refund-1"), any());

        ArgumentCaptor<IncomingRefundRequest> captor = ArgumentCaptor.forClass(IncomingRefundRequest.class);
        verify(incomingRefundRequestRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(IncomingRefundRequestStatus.PROCESSED);
    }

    @Test
    void pollAndProcess_calledTwiceForSameRequest_doesNotDoubleProcess() {
        IncomingRefundRequest incoming = pending(1L);
        when(incomingRefundRequestRepository.findByStatus(IncomingRefundRequestStatus.PENDING))
                .thenReturn(List.of(incoming))
                .thenReturn(List.of());
        when(refundService.refund(eq(1L), eq("refund-1"), any()))
                .thenReturn(new RefundResponse(1L, 1L, "refund-1", BigDecimal.valueOf(75), "USD",
                        RefundStatus.PROCESSED, "customer request"));

        refundRequestPoller.pollAndProcess();
        refundRequestPoller.pollAndProcess();

        verify(refundService, times(1)).refund(eq(1L), eq("refund-1"), any());
    }
}
