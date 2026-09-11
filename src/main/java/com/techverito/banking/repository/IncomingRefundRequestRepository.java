package com.techverito.banking.repository;

import com.techverito.banking.entity.IncomingRefundRequest;
import com.techverito.banking.entity.IncomingRefundRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncomingRefundRequestRepository extends JpaRepository<IncomingRefundRequest, Long> {
    List<IncomingRefundRequest> findByStatus(IncomingRefundRequestStatus status);
}
