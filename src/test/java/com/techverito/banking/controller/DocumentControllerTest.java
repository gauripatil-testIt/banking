package com.techverito.banking.controller;

import com.techverito.banking.dto.DocumentDownloadResult;
import com.techverito.banking.dto.DocumentResponse;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.exception.DocumentContentUnavailableException;
import com.techverito.banking.exception.DocumentValidationException;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.service.DocumentDownloadService;
import com.techverito.banking.service.DocumentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
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

    @MockBean
    DocumentDownloadService documentDownloadService;

    private DocumentResponse response(Long id, String originalFileName) {
        return new DocumentResponse(id, 1L, originalFileName, DocumentType.ID_PROOF, 4L,
                Instant.parse("2024-05-02T10:15:30Z"), "ops.user", "verified copy");
    }

    private MockMultipartFile file(String originalFileName) {
        return new MockMultipartFile("file", originalFileName, "application/pdf",
                "pdf-content".getBytes(StandardCharsets.UTF_8));
    }

    private DocumentDownloadResult downloadResult(String originalFileName, MediaType mediaType, byte[] content) {
        return new DocumentDownloadResult(new ByteArrayResource(content), originalFileName, mediaType, content.length);
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

    @Test
    void GET_customers_id_documents_documentId_returns200AsAttachmentWithOriginalName() throws Exception {
        byte[] content = "pdf-content".getBytes(StandardCharsets.UTF_8);
        when(documentDownloadService.download(1L, 7L, "ops.user"))
                .thenReturn(downloadResult("proof.pdf", MediaType.APPLICATION_PDF, content));

        MvcResult result = mockMvc.perform(get("/customers/1/documents/7").param("downloadedBy", "ops.user"))
                .andExpect(status().isOk())
                .andReturn();

        String contentDisposition = result.getResponse().getHeader("Content-Disposition");
        assertThat(contentDisposition).startsWith("attachment");
        assertThat(ContentDisposition.parse(contentDisposition).getFilename()).isEqualTo("proof.pdf");
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF.toString());
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(content);
    }

    @Test
    void GET_download_nonEnglishFileName_isPreservedInContentDisposition() throws Exception {
        String originalFileName = "pán kyc (final) [übersicht].png";
        byte[] content = "png-content".getBytes(StandardCharsets.UTF_8);
        when(documentDownloadService.download(1L, 7L, "ops.user"))
                .thenReturn(downloadResult(originalFileName, MediaType.IMAGE_PNG, content));

        MvcResult result = mockMvc.perform(get("/customers/1/documents/7").param("downloadedBy", "ops.user"))
                .andExpect(status().isOk())
                .andReturn();

        String contentDisposition = result.getResponse().getHeader("Content-Disposition");
        assertThat(contentDisposition).contains("filename*=UTF-8''");
        assertThat(ContentDisposition.parse(contentDisposition).getFilename()).isEqualTo(originalFileName);
        assertThat(result.getResponse().getContentType()).isEqualTo(MediaType.IMAGE_PNG.toString());
        assertThat(result.getResponse().getContentAsByteArray()).isEqualTo(content);
    }

    @Test
    void GET_download_contentTypeMatchesPdfJpgAndPng() throws Exception {
        byte[] content = "content".getBytes(StandardCharsets.UTF_8);
        when(documentDownloadService.download(1L, 7L, "ops.user"))
                .thenReturn(downloadResult("proof.pdf", MediaType.APPLICATION_PDF, content));
        when(documentDownloadService.download(1L, 8L, "ops.user"))
                .thenReturn(downloadResult("scan.jpg", MediaType.IMAGE_JPEG, content));
        when(documentDownloadService.download(1L, 9L, "ops.user"))
                .thenReturn(downloadResult("scan.png", MediaType.IMAGE_PNG, content));

        MvcResult pdf = mockMvc.perform(get("/customers/1/documents/7").param("downloadedBy", "ops.user"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult jpg = mockMvc.perform(get("/customers/1/documents/8").param("downloadedBy", "ops.user"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult png = mockMvc.perform(get("/customers/1/documents/9").param("downloadedBy", "ops.user"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(pdf.getResponse().getContentType()).isEqualTo(MediaType.APPLICATION_PDF.toString());
        assertThat(jpg.getResponse().getContentType()).isEqualTo(MediaType.IMAGE_JPEG.toString());
        assertThat(png.getResponse().getContentType()).isEqualTo(MediaType.IMAGE_PNG.toString());
    }

    @Test
    void GET_download_maxSizeDocument_returnsFullContent() throws Exception {
        byte[] content = new byte[(int) DataSize.ofMegabytes(10).toBytes()];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 251);
        }
        when(documentDownloadService.download(1L, 7L, "ops.user"))
                .thenReturn(downloadResult("big.pdf", MediaType.APPLICATION_PDF, content));

        MvcResult result = mockMvc.perform(get("/customers/1/documents/7").param("downloadedBy", "ops.user"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] downloaded = result.getResponse().getContentAsByteArray();
        assertThat(downloaded).hasSize((int) DataSize.ofMegabytes(10).toBytes());
        assertThat(downloaded).isEqualTo(content);
    }

    @Test
    void GET_download_twoConcurrentRequests_bothReceiveCompleteContent() throws Exception {
        byte[] content = "concurrent-content".getBytes(StandardCharsets.UTF_8);
        when(documentDownloadService.download(1L, 7L, "ops.user"))
                .thenReturn(downloadResult("proof.pdf", MediaType.APPLICATION_PDF, content));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<MvcResult> request = () -> mockMvc.perform(get("/customers/1/documents/7")
                            .param("downloadedBy", "ops.user"))
                    .andExpect(status().isOk())
                    .andReturn();

            Future<MvcResult> first = executor.submit(request);
            Future<MvcResult> second = executor.submit(request);
            MvcResult firstResult = first.get(10, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(10, TimeUnit.SECONDS);

            assertThat(firstResult.getResponse().getContentAsByteArray()).isEqualTo(content);
            assertThat(secondResult.getResponse().getContentAsByteArray()).isEqualTo(content);
            verify(documentDownloadService, times(2)).download(1L, 7L, "ops.user");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void GET_download_unknownDocument_returns404ProblemDetail() throws Exception {
        when(documentDownloadService.download(1L, 9L, "ops.user"))
                .thenThrow(new ResourceNotFoundException("Document", 9L));

        mockMvc.perform(get("/customers/1/documents/9").param("downloadedBy", "ops.user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Document not found with id: 9"));
    }

    @Test
    void GET_download_documentOfAnotherCustomer_returnsSame404() throws Exception {
        when(documentDownloadService.download(1L, 77L, "ops.user"))
                .thenThrow(new ResourceNotFoundException("Document", 77L));

        MvcResult result = mockMvc.perform(get("/customers/1/documents/77").param("downloadedBy", "ops.user"))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"type\"").contains("\"title\"")
                .contains("\"status\":404")
                .contains("Document not found with id: 77");
    }

    @Test
    void GET_download_storedFileMissing_returns404WithReuploadMessage() throws Exception {
        when(documentDownloadService.download(1L, 7L, "ops.user"))
                .thenThrow(new DocumentContentUnavailableException("stored-7.pdf"));

        mockMvc.perform(get("/customers/1/documents/7").param("downloadedBy", "ops.user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value(
                        "The file for this document is no longer available. Please upload the document again."));
    }

    @Test
    void GET_download_missingDownloadedBy_returns400ProblemDetail() throws Exception {
        MvcResult result = mockMvc.perform(get("/customers/1/documents/7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("downloadedBy");
    }
}
