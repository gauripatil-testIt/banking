package com.techverito.banking.service;

import com.techverito.banking.dto.DocumentDownloadResult;
import com.techverito.banking.entity.CustomerDocument;
import com.techverito.banking.entity.DocumentDownload;
import com.techverito.banking.exception.DocumentValidationException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.CustomerDocumentRepository;
import com.techverito.banking.repository.DocumentDownloadRepository;
import com.techverito.banking.storage.DocumentStorage;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

@Service
@Transactional
public class DocumentDownloadService {

    private final CustomerDocumentRepository documentRepository;
    private final DocumentDownloadRepository downloadRepository;
    private final DocumentStorage storage;

    public DocumentDownloadService(CustomerDocumentRepository documentRepository,
                                   DocumentDownloadRepository downloadRepository,
                                   DocumentStorage storage) {
        this.documentRepository = documentRepository;
        this.downloadRepository = downloadRepository;
        this.storage = storage;
    }

    public DocumentDownloadResult download(Long customerId, Long documentId, String downloadedBy) {
        if (downloadedBy == null || downloadedBy.isBlank()) {
            throw new DocumentValidationException("The name of the user downloading the document is required.");
        }
        CustomerDocument document = documentRepository.findById(documentId)
                .filter(found -> Objects.equals(found.getCustomer().getId(), customerId))
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));

        Resource resource = storage.load(document.getStoredFileName());
        downloadRepository.save(DocumentDownload.builder()
                .document(document)
                .downloadedBy(downloadedBy)
                .downloadedAt(Instant.now())
                .build());
        return new DocumentDownloadResult(resource, document.getOriginalFileName(),
                mediaTypeOf(document.getStoredFileName()), document.getSizeBytes());
    }

    private MediaType mediaTypeOf(String fileName) {
        return switch (extensionOf(fileName)) {
            case "pdf" -> MediaType.APPLICATION_PDF;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
