package com.techverito.banking.storage;

import com.techverito.banking.config.S3Properties;
import com.techverito.banking.exception.DocumentContentUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the S3 adapter against a mocked client: no AWS account, no
 * credentials and no network access are involved.
 */
class S3DocumentStorageTest {

    private static final String BUCKET = "bank-test-bucket";

    private static final String REUPLOAD_MESSAGE =
            "The file for this document is no longer available. Please upload the document again.";

    private S3Client s3Client;

    private S3DocumentStorage storage;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        storage = new S3DocumentStorage(s3Client, new S3Properties(BUCKET, "us-east-1", null));
    }

    private byte[] content(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private S3Exception s3Error(int statusCode) {
        // In the SDK version this build pins (awssdk.version 2.31.0), S3Exception.Builder#build()
        // is declared to return the base AwsServiceException even though it constructs an S3Exception.
        return (S3Exception) S3Exception.builder()
                .message("S3 request failed with status " + statusCode)
                .statusCode(statusCode)
                .build();
    }

    @Test
    void store_putsUnderCustomerPrefixWithUuidKey() throws Exception {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());
        byte[] uploaded = content("pdf-content");

        String key = storage.store(uploaded, "kyc proof (final) [übersicht].pdf", 1L);

        assertThat(key).matches("customers/1/[0-9a-f\\-]{36}\\.pdf");

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        ArgumentCaptor<RequestBody> bodyCaptor = ArgumentCaptor.forClass(RequestBody.class);
        verify(s3Client).putObject(requestCaptor.capture(), bodyCaptor.capture());

        PutObjectRequest request = requestCaptor.getValue();
        assertThat(request.bucket()).isEqualTo(BUCKET);
        assertThat(request.key()).isEqualTo(key);
        assertThat(bodyCaptor.getValue().contentStreamProvider().newStream().readAllBytes()).isEqualTo(uploaded);
    }

    @Test
    void store_appliesSseAes256() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().build());

        storage.store(content("pdf-content"), "proof.pdf", 7L);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));

        PutObjectRequest request = captor.getValue();
        assertThat(request.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(request.metadata()).containsEntry("original-file-name", "proof.pdf");
        assertThat(request.key()).startsWith("customers/7/");
    }

    @Test
    void store_clientFailure_throwsStoreFailureMessage() {
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(s3Error(403));

        assertThatThrownBy(() -> storage.store(content("pdf-content"), "proof.pdf", 1L))
                .isInstanceOf(DocumentStorageException.class)
                .hasMessage("The document could not be stored. Please try again.");
    }

    @Test
    void load_missingKey_throwsContentUnavailableWithReuploadMessage() {
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder()
                        .message("The specified key does not exist.")
                        .statusCode(404)
                        .build());

        assertThatThrownBy(() -> storage.load("customers/1/missing.pdf"))
                .isInstanceOfSatisfying(DocumentContentUnavailableException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo(REUPLOAD_MESSAGE);
                    assertThat(ex.getStoredFileName()).isEqualTo("customers/1/missing.pdf");
                });
    }

    @Test
    void load_notFoundS3Exception_throwsContentUnavailableWithReuploadMessage() {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenThrow(s3Error(404));

        assertThatThrownBy(() -> storage.load("customers/1/missing.pdf"))
                .isInstanceOf(DocumentContentUnavailableException.class)
                .hasMessage(REUPLOAD_MESSAGE);
    }

    @Test
    void load_existingKey_returnsStreamingResource() throws Exception {
        byte[] stored = content("pdf-content");
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenReturn(new ResponseInputStream<>(GetObjectResponse.builder().build(),
                        AbortableInputStream.create(new ByteArrayInputStream(stored))));

        Resource resource = storage.load("customers/1/stored.pdf");

        assertThat(resource.getInputStream().readAllBytes()).isEqualTo(stored);

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo("customers/1/stored.pdf");
    }

    @Test
    void delete_missingKey_doesNotThrow() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(s3Error(404));

        assertThatCode(() -> storage.delete("customers/1/missing.pdf")).doesNotThrowAnyException();
    }

    @Test
    void delete_noSuchKey_doesNotThrow() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder()
                        .message("The specified key does not exist.")
                        .statusCode(404)
                        .build());

        assertThatCode(() -> storage.delete("customers/1/missing.pdf")).doesNotThrowAnyException();
    }

    @Test
    void delete_clientFailure_throwsDocumentStorageException() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class))).thenThrow(s3Error(403));

        assertThatThrownBy(() -> storage.delete("customers/1/stored.pdf"))
                .isInstanceOf(DocumentStorageException.class)
                .hasMessageContaining("could not be deleted");
    }

    @Test
    void delete_existingKey_deletesTheKeyInTheBucket() {
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenReturn(DeleteObjectResponse.builder().build());

        storage.delete("customers/1/stored.pdf");

        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo("customers/1/stored.pdf");
    }
}
