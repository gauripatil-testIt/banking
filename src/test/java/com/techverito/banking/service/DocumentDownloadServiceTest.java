package com.techverito.banking.service;

import com.techverito.banking.dto.DocumentDownloadResult;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerDocument;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.DocumentDownload;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.exception.DocumentContentUnavailableException;
import com.techverito.banking.exception.DocumentValidationException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.CustomerDocumentRepository;
import com.techverito.banking.repository.DocumentDownloadRepository;
import com.techverito.banking.storage.DocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentDownloadServiceTest {

    @Mock
    CustomerDocumentRepository documentRepository;

    @Mock
    DocumentDownloadRepository downloadRepository;

    @Mock
    DocumentStorage storage;

    private DocumentDownloadService documentDownloadService;

    @BeforeEach
    void setUp() {
        documentDownloadService = new DocumentDownloadService(documentRepository, downloadRepository, storage);
    }

    private Customer customer(Long id) {
        return Customer.builder()
                .id(id).firstName("John").lastName("Doe")
                .email("john@example.com").phone("123")
                .status(CustomerStatus.ACTIVE).build();
    }

    private CustomerDocument document(Long id, Customer customer, String originalFileName,
                                      String storedFileName, long sizeBytes) {
        return CustomerDocument.builder()
                .id(id).customer(customer)
                .documentType(DocumentType.ID_PROOF)
                .originalFileName(originalFileName)
                .storedFileName(storedFileName)
                .sizeBytes(sizeBytes)
                .uploadedBy("ops.user")
                .uploadedAt(Instant.parse("2024-05-02T10:15:30Z"))
                .build();
    }

    @Test
    void download_existingDocument_returnsMetadataAndRecordsDownload() {
        CustomerDocument document = document(7L, customer(1L), "proof.pdf", "stored-7.pdf", 4L);
        Resource resource = mock(Resource.class);
        when(documentRepository.findById(7L)).thenReturn(Optional.of(document));
        when(storage.load("stored-7.pdf")).thenReturn(resource);

        DocumentDownloadResult result = documentDownloadService.download(1L, 7L, "ops.user");

        assertThat(result.originalFileName()).isEqualTo("proof.pdf");
        assertThat(result.mediaType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(result.resource()).isSameAs(resource);
        assertThat(result.sizeBytes()).isEqualTo(4L);

        ArgumentCaptor<DocumentDownload> captor = ArgumentCaptor.forClass(DocumentDownload.class);
        verify(downloadRepository).save(captor.capture());
        DocumentDownload persisted = captor.getValue();
        assertThat(persisted.getDocument()).isSameAs(document);
        assertThat(persisted.getDownloadedBy()).isEqualTo("ops.user");
        assertThat(persisted.getDownloadedAt()).isNotNull();
    }

    @Test
    void download_nonEnglishFileName_returnsNameVerbatim() {
        String originalFileName = "pán kyc (final) [übersicht].png";
        CustomerDocument document = document(7L, customer(1L), originalFileName, "stored-7.png", 9L);
        Resource resource = mock(Resource.class);
        when(documentRepository.findById(7L)).thenReturn(Optional.of(document));
        when(storage.load("stored-7.png")).thenReturn(resource);

        DocumentDownloadResult result = documentDownloadService.download(1L, 7L, "ops.user");

        assertThat(result.originalFileName()).isEqualTo(originalFileName);
        assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE_PNG);
        assertThat(result.resource()).isSameAs(resource);
        verify(downloadRepository).save(any(DocumentDownload.class));
    }

    @Test
    void download_jpgDocument_returnsJpegMediaType() {
        CustomerDocument document = document(8L, customer(1L), "scan.jpg", "stored-8.jpg", 5L);
        Resource resource = mock(Resource.class);
        when(documentRepository.findById(8L)).thenReturn(Optional.of(document));
        when(storage.load("stored-8.jpg")).thenReturn(resource);

        DocumentDownloadResult result = documentDownloadService.download(1L, 8L, "ops.user");

        assertThat(result.mediaType()).isEqualTo(MediaType.IMAGE_JPEG);
    }

    @Test
    void download_unknownDocument_throwsNotFoundAndRecordsNothing() {
        when(documentRepository.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentDownloadService.download(1L, 5L, "ops.user"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Document not found with id: 5");

        verify(storage, never()).load(any());
        verify(downloadRepository, never()).save(any());
    }

    @Test
    void download_documentOfAnotherCustomer_throwsSameNotFoundAndRecordsNothing() {
        CustomerDocument otherCustomersDocument = document(77L, customer(2L), "proof.pdf", "stored-77.pdf", 4L);
        when(documentRepository.findById(77L)).thenReturn(Optional.of(otherCustomersDocument));

        assertThatThrownBy(() -> documentDownloadService.download(1L, 77L, "ops.user"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Document not found with id: 77");

        verify(storage, never()).load(any());
        verify(downloadRepository, never()).save(any());
    }

    @Test
    void download_missingStoredFile_propagatesContentUnavailableAndRecordsNothing() {
        CustomerDocument document = document(7L, customer(1L), "proof.pdf", "stored-7.pdf", 4L);
        when(documentRepository.findById(7L)).thenReturn(Optional.of(document));
        when(storage.load("stored-7.pdf")).thenThrow(new DocumentContentUnavailableException("stored-7.pdf"));

        assertThatThrownBy(() -> documentDownloadService.download(1L, 7L, "ops.user"))
                .isInstanceOf(DocumentContentUnavailableException.class)
                .hasMessage("The file for this document is no longer available. Please upload the document again.");

        verify(downloadRepository, never()).save(any());
    }

    @Test
    void download_blankDownloadedBy_throwsValidationAndRecordsNothing() {
        assertThatThrownBy(() -> documentDownloadService.download(1L, 7L, "  "))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("downloading");

        verify(storage, never()).load(any());
        verify(downloadRepository, never()).save(any());
    }

    @Test
    void download_maxSizeDocument_returnsStreamingResource() throws Exception {
        long maxSize = DataSize.ofMegabytes(10).toBytes();
        CustomerDocument document = document(7L, customer(1L), "big.pdf", "stored-big.pdf", maxSize);
        Resource resource = new ByteArrayResource(new byte[(int) maxSize]);
        when(documentRepository.findById(7L)).thenReturn(Optional.of(document));
        when(storage.load("stored-big.pdf")).thenReturn(resource);

        DocumentDownloadResult result = documentDownloadService.download(1L, 7L, "ops.user");

        assertThat(result.resource()).isSameAs(resource);
        assertThat(result.resource().contentLength()).isEqualTo(maxSize);
        assertThat(result.sizeBytes()).isEqualTo(maxSize);
        assertThat(result.mediaType()).isEqualTo(MediaType.APPLICATION_PDF);
        verify(downloadRepository).save(any(DocumentDownload.class));
    }
}
