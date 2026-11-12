package com.techverito.banking.repository;

import com.techverito.banking.entity.CustomerDocument;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerDocumentRepository extends JpaRepository<CustomerDocument, Long> {
    List<CustomerDocument> findByCustomer_IdOrderByUploadedAtDescIdDesc(Long customerId);
}
