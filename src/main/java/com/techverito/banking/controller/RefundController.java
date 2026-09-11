package com.techverito.banking.controller;

import com.techverito.banking.dto.RefundRequest;
import com.techverito.banking.dto.RefundResponse;
import com.techverito.banking.repository.RefundRepository;
import com.techverito.banking.service.RefundService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments/{paymentId}/refunds")
public class RefundController {

    private final RefundService refundService;
    private final RefundRepository refundRepository;

    public RefundController(RefundService refundService, RefundRepository refundRepository) {
        this.refundService = refundService;
        this.refundRepository = refundRepository;
    }

    /**
     * Refunds a payment, in full or partially. The refundId is supplied by the client via the
     * X-Refund-Id header and is used, together with paymentId, to guarantee idempotency: replaying
     * the same (paymentId, refundId) pair does not perform the refund twice and returns the
     * previously recorded result with a 200 status instead of 201.
     */
    @PostMapping
    public ResponseEntity<RefundResponse> refund(@PathVariable Long paymentId,
                                                  @RequestHeader("X-Refund-Id") String refundId,
                                                  @Valid @RequestBody RefundRequest request) {
        boolean alreadyExists = refundRepository.findByPayment_IdAndRefundId(paymentId, refundId).isPresent();
        RefundResponse response = refundService.refund(paymentId, refundId, request);
        HttpStatus status = alreadyExists ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(response);
    }
}
