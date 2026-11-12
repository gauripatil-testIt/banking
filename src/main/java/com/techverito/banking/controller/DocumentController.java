package com.techverito.banking.controller;

import com.techverito.banking.dto.DocumentResponse;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/customers/{customerId}/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
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
}
