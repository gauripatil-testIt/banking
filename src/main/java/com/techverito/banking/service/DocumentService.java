package com.techverito.banking.service;

import com.techverito.banking.config.DocumentProperties;
import com.techverito.banking.dto.DocumentResponse;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerDocument;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.exception.DocumentValidationException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.CustomerDocumentRepository;
import com.techverito.banking.repository.CustomerRepository;
import com.techverito.banking.storage.DocumentStorage;
import com.techverito.banking.storage.DocumentStorageException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

@Service
@Transactional
public class DocumentService {

    private final CustomerDocumentRepository documentRepository;
    private final CustomerRepository customerRepository;
    private final DocumentStorage storage;
    private final DocumentProperties properties;

    public DocumentService(CustomerDocumentRepository documentRepository,
                           CustomerRepository customerRepository,
                           DocumentStorage storage,
                           DocumentProperties properties) {
        this.documentRepository = documentRepository;
        this.customerRepository = customerRepository;
        this.storage = storage;
        this.properties = properties;
    }

    public DocumentResponse upload(Long customerId, MultipartFile file, DocumentType documentType,
                                   String uploadedBy, String note) {
        Customer customer = findCustomerOrThrow(customerId);
        if (documentType == null) {
            throw new DocumentValidationException("A document type is required. " + allowedRule());
        }
        if (uploadedBy == null || uploadedBy.isBlank()) {
            throw new DocumentValidationException("The name of the user uploading the document is required.");
        }
        validateFile(file);

        String originalFileName = file.getOriginalFilename();
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException ex) {
            throw new DocumentStorageException("Could not read the uploaded file: " + originalFileName, ex);
        }

        String storedFileName = storage.store(content, originalFileName);
        try {
            CustomerDocument document = CustomerDocument.builder()
                    .customer(customer)
                    .documentType(documentType)
                    .originalFileName(originalFileName)
                    .storedFileName(storedFileName)
                    .sizeBytes(content.length)
                    .note(note)
                    .uploadedBy(uploadedBy)
                    .uploadedAt(Instant.now())
                    .build();
            return DocumentResponse.from(documentRepository.save(document));
        } catch (RuntimeException ex) {
            deleteStoredFile(storedFileName, ex);
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listByCustomer(Long customerId) {
        findCustomerOrThrow(customerId);
        return documentRepository.findByCustomer_IdOrderByUploadedAtDescIdDesc(customerId).stream()
                .map(DocumentResponse::from)
                .toList();
    }

    private Customer findCustomerOrThrow(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DocumentValidationException("The file is empty. Please choose a file to upload.");
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw new DocumentValidationException("The file is too large. " + allowedRule());
        }
        String extension = extensionOf(file.getOriginalFilename());
        boolean allowed = properties.allowedExtensions().stream()
                .anyMatch(configured -> configured != null && configured.trim().equalsIgnoreCase(extension));
        if (!allowed) {
            throw new DocumentValidationException("The file type is not allowed. " + allowedRule());
        }
    }

    private String allowedRule() {
        List<String> types = properties.allowedExtensions().stream()
                .filter(configured -> configured != null && !configured.isBlank())
                .map(configured -> configured.trim().toUpperCase(Locale.ROOT))
                .toList();
        String typeList;
        if (types.isEmpty()) {
            typeList = "";
        } else if (types.size() == 1) {
            typeList = types.get(0);
        } else {
            typeList = String.join(", ", types.subList(0, types.size() - 1)) + " and " + types.get(types.size() - 1);
        }
        return "Only " + typeList + " files are allowed, up to " + maxSizeLabel() + ".";
    }

    private String maxSizeLabel() {
        long megabytes = properties.maxFileSize().toMegabytes();
        return (megabytes > 0) ? megabytes + " MB" : properties.maxFileSize().toBytes() + " bytes";
    }

    private String extensionOf(String originalFileName) {
        if (originalFileName == null) {
            return "";
        }
        int dot = originalFileName.lastIndexOf('.');
        if (dot < 0 || dot == originalFileName.length() - 1) {
            return "";
        }
        return originalFileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private void deleteStoredFile(String storedFileName, RuntimeException original) {
        try {
            storage.delete(storedFileName);
        } catch (RuntimeException suppression) {
            original.addSuppressed(suppression);
        }
    }
}
