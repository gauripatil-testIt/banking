package com.techverito.banking.service;

import com.techverito.banking.dto.RefundRequest;
import com.techverito.banking.entity.IncomingRefundRequest;
import com.techverito.banking.entity.IncomingRefundRequestStatus;
import com.techverito.banking.repository.IncomingRefundRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Polls the database-backed queue of incoming refund requests that clients sent (possibly
 * while this service was down) and applies them via {@link RefundService}. RefundService is
 * idempotent on (paymentId, refundId), so re-processing an already-applied request (e.g. if the
 * poller is restarted mid-run) never results in a duplicate refund.
 */
@Component
public class RefundRequestPoller {

    private static final Logger log = LoggerFactory.getLogger(RefundRequestPoller.class);

    private final IncomingRefundRequestRepository incomingRefundRequestRepository;
    private final RefundService refundService;

    public RefundRequestPoller(IncomingRefundRequestRepository incomingRefundRequestRepository,
                                RefundService refundService) {
        this.incomingRefundRequestRepository = incomingRefundRequestRepository;
        this.refundService = refundService;
    }

    @Scheduled(fixedDelayString = "${banking.refund-poller.fixed-delay-ms:5000}")
    public void pollAndProcess() {
        List<IncomingRefundRequest> pending =
                incomingRefundRequestRepository.findByStatus(IncomingRefundRequestStatus.PENDING);

        for (IncomingRefundRequest incoming : pending) {
            processOne(incoming);
        }
    }

    private void processOne(IncomingRefundRequest incoming) {
        try {
            refundService.refund(
                    incoming.getPaymentId(),
                    incoming.getRefundId(),
                    new RefundRequest(incoming.getAmount(), incoming.getCurrency(), incoming.getReason())
            );
            incoming.setStatus(IncomingRefundRequestStatus.PROCESSED);
            incomingRefundRequestRepository.save(incoming);
        } catch (Exception e) {
            log.error("Failed to process incoming refund request id={} paymentId={} refundId={}",
                    incoming.getId(), incoming.getPaymentId(), incoming.getRefundId(), e);
            incoming.setStatus(IncomingRefundRequestStatus.FAILED);
            incomingRefundRequestRepository.save(incoming);
        }
    }
}
