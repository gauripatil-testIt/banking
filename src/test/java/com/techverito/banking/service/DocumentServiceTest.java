package com.techverito.banking.service;

import com.techverito.banking.config.DocumentProperties;
import com.techverito.banking.dto.DocumentResponse;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerDocument;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.DocumentUploadedPayload;
import com.techverito.banking.event.EventBus;
import com.techverito.banking.event.EventType;
import com.techverito.banking.exception.DocumentValidationException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.CustomerDocumentRepository;
import com.techverito.banking.repository.CustomerRepository;
import com.techverito.banking.storage.DocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    CustomerDocumentRepository documentRepository;

    @Mock
    CustomerRepository customerRepository;

    @Mock
    DocumentStorage storage;

    @Mock
    EventBus eventBus;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        DocumentProperties properties = new DocumentProperties(
                Path.of("./target/test-documents"),
                Set.of("pdf", "jpg", "png"),
                DataSize.ofMegabytes(10));
        documentService = new DocumentService(documentRepository, customerRepository, storage, properties, eventBus);
    }

    private Customer customer(Long id) {
        return Customer.builder()
                .id(id).firstName("John").lastName("Doe")
                .email("john@example.com").phone("123")
                .status(CustomerStatus.ACTIVE).build();
    }

    private CustomerDocument withId(CustomerDocument saved, Long id) {
        return CustomerDocument.builder()
                .id(id)
                .customer(saved.getCustomer())
                .documentType(saved.getDocumentType())
                .originalFileName(saved.getOriginalFileName())
                .storedFileName(saved.getStoredFileName())
                .sizeBytes(saved.getSizeBytes())
                .note(saved.getNote())
                .uploadedBy(saved.getUploadedBy())
                .uploadedAt(saved.getUploadedAt())
                .build();
    }

    private CustomerDocument document(Long id, Instant uploadedAt, String storedFileName) {
        return CustomerDocument.builder()
                .id(id).customer(customer(1L))
                .documentType(DocumentType.ID_PROOF)
                .originalFileName("proof.pdf")
                .storedFileName(storedFileName)
                .sizeBytes(4L)
                .uploadedBy("ops.user")
                .uploadedAt(uploadedAt)
                .build();
    }

    private MultipartFile pdf(String originalFileName) {
        return new MockMultipartFile("file", originalFileName, "application/pdf",
                "pdf-content".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void upload_validPdf_returnsDocumentWithAllFields() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(storage.store(any(), any())).thenReturn("stored-1.pdf");
        when(documentRepository.save(any(CustomerDocument.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 7L));

        MultipartFile file = pdf("kyc proof (final) [übersicht].pdf");

        DocumentResponse response = documentService.upload(1L, file, DocumentType.ID_PROOF, "ops.user", "verified copy");

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.customerId()).isEqualTo(1L);
        assertThat(response.originalFileName()).isEqualTo("kyc proof (final) [übersicht].pdf");
        assertThat(response.documentType()).isEqualTo(DocumentType.ID_PROOF);
        assertThat(response.sizeBytes()).isEqualTo("pdf-content".getBytes(StandardCharsets.UTF_8).length);
        assertThat(response.uploadedAt()).isNotNull();
        assertThat(response.uploadedBy()).isEqualTo("ops.user");
        assertThat(response.note()).isEqualTo("verified copy");

        ArgumentCaptor<CustomerDocument> captor = ArgumentCaptor.forClass(CustomerDocument.class);
        verify(documentRepository).save(captor.capture());
        CustomerDocument persisted = captor.getValue();
        assertThat(persisted.getCustomer().getId()).isEqualTo(1L);
        assertThat(persisted.getDocumentType()).isEqualTo(DocumentType.ID_PROOF);
        assertThat(persisted.getUploadedBy()).isEqualTo("ops.user");
        assertThat(persisted.getUploadedAt()).isNotNull();
        assertThat(persisted.getStoredFileName()).isEqualTo("stored-1.pdf");
        assertThat(persisted.getOriginalFileName()).isEqualTo("kyc proof (final) [übersicht].pdf");
    }

    @Test
    void upload_publishesDocumentUploadedEvent() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(storage.store(any(), any())).thenReturn("stored-1.pdf");
        when(documentRepository.save(any(CustomerDocument.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 7L));

        documentService.upload(1L, pdf("proof.pdf"), DocumentType.ID_PROOF, "ops.user", null);

        ArgumentCaptor<BusinessEvent> captor = ArgumentCaptor.forClass(BusinessEvent.class);
        verify(eventBus).publish(captor.capture(), eq("ops.user"));
        BusinessEvent published = captor.getValue();
        assertThat(published.type()).isEqualTo(EventType.DOCUMENT_UPLOADED);
        assertThat(published.customerId()).isEqualTo(1L);
        assertThat(published.accountId()).isNull();
        assertThat(published.payload()).isInstanceOfSatisfying(DocumentUploadedPayload.class, payload -> {
            assertThat(payload.documentId()).isEqualTo(7L);
            assertThat(payload.documentType()).isEqualTo(DocumentType.ID_PROOF);
            assertThat(payload.originalFileName()).isEqualTo("proof.pdf");
            assertThat(payload.sizeBytes()).isEqualTo("pdf-content".getBytes(StandardCharsets.UTF_8).length);
            assertThat(payload.uploadedBy()).isEqualTo("ops.user");
        });
    }

    @Test
    void upload_rejectedFile_publishesNothing() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        MultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> documentService.upload(1L, file, DocumentType.ID_PROOF, "ops.user", null))
                .isInstanceOf(DocumentValidationException.class);

        verify(eventBus, never()).publish(any(), any());
    }

    @Test
    void upload_unsupportedType_throwsValidationExceptionAndSavesNothing() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        MultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain",
                "hello".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> documentService.upload(1L, file, DocumentType.ID_PROOF, "ops.user", null))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("PDF")
                .hasMessageContaining("JPG")
                .hasMessageContaining("PNG");

        verify(storage, never()).store(any(), any());
        verify(documentRepository, never()).save(any(CustomerDocument.class));
    }

    @Test
    void upload_oversizedFile_throwsValidationExceptionWithLimitAndSavesNothing() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        byte[] tooLarge = new byte[(int) DataSize.ofMegabytes(10).toBytes() + 1];
        MultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", tooLarge);

        assertThatThrownBy(() -> documentService.upload(1L, file, DocumentType.ID_PROOF, "ops.user", null))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("10 MB");

        verify(storage, never()).store(any(), any());
        verify(documentRepository, never()).save(any(CustomerDocument.class));
    }

    @Test
    void upload_fileExactlyAtLimit_isAccepted() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(storage.store(any(), any())).thenReturn("stored-limit.pdf");
        when(documentRepository.save(any(CustomerDocument.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), 11L));
        byte[] atLimit = new byte[(int) DataSize.ofMegabytes(10).toBytes()];
        MultipartFile file = new MockMultipartFile("file", "big.pdf", "application/pdf", atLimit);

        DocumentResponse response = documentService.upload(1L, file, DocumentType.SIGNED_FORM, "ops.user", null);

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.sizeBytes()).isEqualTo(atLimit.length);
    }

    @Test
    void upload_emptyFile_throwsValidationExceptionAndSavesNothing() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        MultipartFile file = new MockMultipartFile("file", "empty.pdf", "application/pdf", new byte[0]);

        assertThatThrownBy(() -> documentService.upload(1L, file, DocumentType.ID_PROOF, "ops.user", null))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("empty");

        verify(storage, never()).store(any(), any());
        verify(documentRepository, never()).save(any(CustomerDocument.class));
    }

    @Test
    void upload_blankUploadedBy_throwsValidationExceptionAndSavesNothing() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        MultipartFile file = pdf("proof.pdf");

        assertThatThrownBy(() -> documentService.upload(1L, file, DocumentType.ID_PROOF, "  ", null))
                .isInstanceOf(DocumentValidationException.class)
                .hasMessageContaining("uploading");

        verify(storage, never()).store(any(), any());
        verify(documentRepository, never()).save(any(CustomerDocument.class));
    }

    @Test
    void upload_unknownCustomer_throwsNotFoundAndStoresNothing() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());
        MultipartFile file = pdf("proof.pdf");

        assertThatThrownBy(() -> documentService.upload(99L, file, DocumentType.ID_PROOF, "ops.user", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer")
                .hasMessageContaining("99");

        verify(storage, never()).store(any(), any());
        verify(documentRepository, never()).save(any(CustomerDocument.class));
    }

    @Test
    void upload_duplicateOriginalFileName_keepsBothDocuments() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(storage.store(any(), any())).thenReturn("stored-first.pdf", "stored-second.pdf");
        AtomicLong nextId = new AtomicLong(1L);
        when(documentRepository.save(any(CustomerDocument.class)))
                .thenAnswer(invocation -> withId(invocation.getArgument(0), nextId.getAndIncrement()));

        DocumentResponse first = documentService.upload(1L, pdf("statement.pdf"), DocumentType.ID_PROOF, "ops.user", null);
        DocumentResponse second = documentService.upload(1L, pdf("statement.pdf"), DocumentType.ID_PROOF, "ops.user", null);

        assertThat(first.id()).isNotEqualTo(second.id());
        ArgumentCaptor<CustomerDocument> captor = ArgumentCaptor.forClass(CustomerDocument.class);
        verify(documentRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CustomerDocument::getOriginalFileName)
                .containsOnly("statement.pdf");
        assertThat(captor.getAllValues())
                .extracting(CustomerDocument::getStoredFileName)
                .containsExactly("stored-first.pdf", "stored-second.pdf");
        verify(storage, never()).delete(any());
    }

    @Test
    void upload_saveFails_deletesStoredFileAndPropagates() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        when(storage.store(any(), any())).thenReturn("stored-rollback.pdf");
        when(documentRepository.save(any(CustomerDocument.class)))
                .thenThrow(new DataIntegrityViolationException("insert failed"));

        MultipartFile file = pdf("proof.pdf");

        assertThatThrownBy(() -> documentService.upload(1L, file, DocumentType.ADDRESS_PROOF, "ops.user", null))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(storage).delete("stored-rollback.pdf");
        verify(storage, times(1)).delete(any());
    }

    @Test
    void listByCustomer_returnsDocumentsNewestFirst() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));
        CustomerDocument newest = document(2L, Instant.parse("2024-05-02T10:15:30Z"), "stored-2.pdf");
        CustomerDocument oldest = document(1L, Instant.parse("2024-05-01T09:00:00Z"), "stored-1.pdf");
        when(documentRepository.findByCustomer_IdOrderByUploadedAtDescIdDesc(1L))
                .thenReturn(List.of(newest, oldest));

        List<DocumentResponse> result = documentService.listByCustomer(1L);

        assertThat(result).extracting(DocumentResponse::id).containsExactly(2L, 1L);
        assertThat(result).extracting(DocumentResponse::customerId).containsOnly(1L);
    }

    @Test
    void listByCustomer_unknownCustomer_throwsNotFound() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.listByCustomer(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer");

        verify(documentRepository, never()).findByCustomer_IdOrderByUploadedAtDescIdDesc(anyLong());
    }
}
