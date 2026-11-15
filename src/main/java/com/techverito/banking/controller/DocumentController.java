package com.techverito.banking.controller;

import com.techverito.banking.dto.DocumentDownloadResult;
import com.techverito.banking.dto.DocumentResponse;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.service.DocumentDownloadService;
import com.techverito.banking.service.DocumentService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/customers/{customerId}/documents")
public class DocumentController {

    private final DocumentService documentService;
    private final DocumentDownloadService documentDownloadService;

    public DocumentController(DocumentService documentService,
                              DocumentDownloadService documentDownloadService) {
        this.documentService = documentService;
        this.documentDownloadService = documentDownloadService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponse upload(@PathVariable Long customerId,
                                   @RequestParam("file") MultipartFile file,
                                   @RequestParam("documentType") DocumentType documentType,
                                   @RequestParam("uploadedBy") String uploadedBy,
                                   @RequestParam(value = "note", required = false) String note) {
        return documentService.upload(customerId, file, documentType, uploadedBy, note);
    }

    @GetMapping
    public List<DocumentResponse> list(@PathVariable Long customerId) {
        return documentService.listByCustomer(customerId);
    }

    @DeleteMapping("/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long customerId, @PathVariable Long documentId) {
        documentService.delete(customerId, documentId);
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<Resource> download(@PathVariable Long customerId,
                                             @PathVariable Long documentId,
                                             @RequestParam("downloadedBy") String downloadedBy) {
        DocumentDownloadResult result = documentDownloadService.download(customerId, documentId, downloadedBy);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(result.originalFileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(result.mediaType())
                .body(result.resource());
    }
}
