package com.techverito.banking.controller;

import com.techverito.banking.dto.DocumentResponse;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.exception.DocumentValidationException;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({DocumentController.class, GlobalExceptionHandler.class})
class DocumentControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    DocumentService documentService;

    private DocumentResponse response(Long id, String originalFileName) {
        return new DocumentResponse(id, 1L, originalFileName, DocumentType.ID_PROOF, 4L,
                Instant.parse("2024-05-02T10:15:30Z"), "ops.user", "verified copy");
    }

    private MockMultipartFile file(String originalFileName) {
        return new MockMultipartFile("file", originalFileName, "application/pdf",
                "pdf-content".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void POST_customers_id_documents_returns201WithDocumentDetails() throws Exception {
        String originalFileName = "kyc proof (final) [übersicht].pdf";
        when(documentService.upload(eq(1L), any(MultipartFile.class), eq(DocumentType.ID_PROOF),
                eq("ops.user"), any())).thenReturn(response(7L, originalFileName));

        mockMvc.perform(multipart("/customers/1/documents")
                        .file(file(originalFileName))
                        .param("documentType", "ID_PROOF")
                        .param("uploadedBy", "ops.user")
                        .param("note", "verified copy"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.customerId").value(1))
                .andExpect(jsonPath("$.originalFileName").value(originalFileName))
                .andExpect(jsonPath("$.documentType").value("ID_PROOF"))
                .andExpect(jsonPath("$.sizeBytes").value(4))
                .andExpect(jsonPath("$.uploadedAt").exists())
                .andExpect(jsonPath("$.uploadedBy").value("ops.user"));

        verify(documentService).upload(eq(1L), any(MultipartFile.class), eq(DocumentType.ID_PROOF),
                eq("ops.user"), eq("verified copy"));
    }

    @Test
    void POST_customers_id_documents_withoutNote_returns201() throws Exception {
        when(documentService.upload(eq(1L), any(MultipartFile.class), eq(DocumentType.SIGNED_FORM),
                eq("ops.user"), any())).thenReturn(response(8L, "form.pdf"));

        mockMvc.perform(multipart("/customers/1/documents")
                        .file(file("form.pdf"))
                        .param("documentType", "SIGNED_FORM")
                        .param("uploadedBy", "ops.user"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(8));

        verify(documentService).upload(eq(1L), any(MultipartFile.class), eq(DocumentType.SIGNED_FORM),
                eq("ops.user"), isNull());
    }

    @Test
    void GET_customers_id_documents_returnsDocumentsInServiceOrder() throws Exception {
        when(documentService.listByCustomer(1L))
                .thenReturn(List.of(response(2L, "newest.pdf"), response(1L, "oldest.pdf")));

        mockMvc.perform(get("/customers/1/documents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(2))
                .andExpect(jsonPath("$[0].originalFileName").value("newest.pdf"))
                .andExpect(jsonPath("$[1].id").value(1))
                .andExpect(jsonPath("$[1].originalFileName").value("oldest.pdf"));
    }

    @Test
    void POST_customers_id_documents_unknownCustomer_returns404() throws Exception {
        when(documentService.upload(eq(99L), any(MultipartFile.class), any(DocumentType.class), any(), any()))
                .thenThrow(new ResourceNotFoundException("Customer", 99L));

        mockMvc.perform(multipart("/customers/99/documents")
                        .file(file("proof.pdf"))
                        .param("documentType", "ID_PROOF")
                        .param("uploadedBy", "ops.user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").exists());
    }

    @Test
    void GET_customers_id_documents_unknownCustomer_returns404() throws Exception {
        when(documentService.listByCustomer(99L)).thenThrow(new ResourceNotFoundException("Customer", 99L));

        mockMvc.perform(get("/customers/99/documents"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void POST_customers_id_documents_rejectedFile_returns400WithRule() throws Exception {
        String rule = "The file type is not allowed. Only PDF, JPG and PNG files are allowed, up to 10 MB.";
        when(documentService.upload(eq(1L), any(MultipartFile.class), any(DocumentType.class), any(), any()))
                .thenThrow(new DocumentValidationException(rule));

        MockMultipartFile wrongType = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/customers/1/documents")
                        .file(wrongType)
                        .param("documentType", "ID_PROOF")
                        .param("uploadedBy", "ops.user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(rule));
    }

    @Test
    void POST_customers_id_documents_missingUploadedBy_returns400() throws Exception {
        mockMvc.perform(multipart("/customers/1/documents")
                        .file(file("proof.pdf"))
                        .param("documentType", "ID_PROOF"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists());
    }
}
