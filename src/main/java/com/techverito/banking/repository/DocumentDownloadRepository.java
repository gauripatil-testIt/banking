package com.techverito.banking.repository;

import com.techverito.banking.entity.DocumentDownload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DocumentDownloadRepository extends JpaRepository<DocumentDownload, Long> {
    List<DocumentDownload> findByDocument_IdOrderByDownloadedAtDescIdDesc(Long documentId);
}
