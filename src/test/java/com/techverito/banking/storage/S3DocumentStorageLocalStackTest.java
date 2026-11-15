package com.techverito.banking.storage;

import com.techverito.banking.config.DocumentProperties;
import com.techverito.banking.config.S3Properties;
import com.techverito.banking.config.StorageType;
import com.techverito.banking.dto.DocumentDownloadResult;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerDocument;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.DocumentDownload;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.exception.DocumentContentUnavailableException;
import com.techverito.banking.repository.CustomerDocumentRepository;
import com.techverito.banking.repository.CustomerRepository;
import com.techverito.banking.repository.DocumentDownloadRepository;
import com.techverito.banking.service.DocumentDownloadService;
import com.techverito.banking.service.DocumentService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.GetPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PublicAccessBlockConfiguration;
import software.amazon.awssdk.services.s3.model.PutBucketEncryptionRequest;
import software.amazon.awssdk.services.s3.model.PutPublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionByDefault;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionConfiguration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryptionRule;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End-to-end proof of the S3 adapter against a real S3 API served by LocalStack.
 *
 * It needs Docker and nothing else: the client is built with the container's
 * endpoint and dummy static credentials, so no AWS account, no AWS region
 * reachability and no real credentials are involved. The tests are skipped when
 * Docker is not available, so the rest of the suite still runs on a machine
 * without it.
 *
 * The bucket is provisioned exactly as the deployment guide says: created, all
 * four block-public-access flags on, and default encryption AES256.
 */
@Testcontainers(disabledWithoutDocker = true)
class S3DocumentStorageLocalStackTest {

    private static final String BUCKET = "bank-documents-test";

    private static final String REGION = "us-east-1";

    private static final String REUPLOAD_MESSAGE =
            "The file for this document is no longer available. Please upload the document again.";

    @Container
    static final LocalStackContainer LOCAL_STACK =
            new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7"))
                    .withServices(LocalStackContainer.Service.S3);

    private static S3Client s3Client;

    private static S3DocumentStorage storage;

    @BeforeAll
    static void provisionBucket() {
        s3Client = S3Client.builder()
                .endpointOverride(LOCAL_STACK.getEndpoint())
                .region(Region.of(REGION))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCAL_STACK.getAccessKey(), LOCAL_STACK.getSecretKey())))
                .forcePathStyle(true)
                .build();
        storage = new S3DocumentStorage(s3Client, new S3Properties(BUCKET, REGION, LOCAL_STACK.getEndpoint()));

        s3Client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        s3Client.putPublicAccessBlock(PutPublicAccessBlockRequest.builder()
                .bucket(BUCKET)
                .publicAccessBlockConfiguration(PublicAccessBlockConfiguration.builder()
                        .blockPublicAcls(true)
                        .ignorePublicAcls(true)
                        .blockPublicPolicy(true)
                        .restrictPublicBuckets(true)
                        .build())
                .build());
        s3Client.putBucketEncryption(PutBucketEncryptionRequest.builder()
                .bucket(BUCKET)
                .serverSideEncryptionConfiguration(ServerSideEncryptionConfiguration.builder()
                        .rules(ServerSideEncryptionRule.builder()
                                .applyServerSideEncryptionByDefault(ServerSideEncryptionByDefault.builder()
                                        .sseAlgorithm(ServerSideEncryption.AES256)
                                        .build())
                                .build())
                        .build())
                .build());
    }

    @AfterAll
    static void closeClient() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    private static byte[] content(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static Customer customer(Long id) {
        return Customer.builder()
                .id(id).firstName("John").lastName("Doe")
                .email("john@example.com").phone("123")
                .status(CustomerStatus.ACTIVE).build();
    }

    private static CustomerDocument document(Long id, Customer customer, String originalFileName,
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

    private static DocumentProperties documentProperties() {
        return new DocumentProperties(Path.of("./target/test-documents/s3-localstack"), Set.of("pdf", "jpg", "png"),
                DataSize.ofMegabytes(10), StorageType.S3);
    }

    /** The object keys currently present under a prefix, as the bucket sees them. */
    private static List<String> keysUnder(String prefix) {
        return s3Client.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(BUCKET)
                        .prefix(prefix)
                        .build())
                .contents().stream()
                .map(S3Object::key)
                .toList();
    }

    @Test
    void store_andLoad_roundTripsContentUnderCustomerPrefix() throws Exception {
        byte[] uploaded = content("pdf-content-round-trip");

        String key = storage.store(uploaded, "statement.pdf", 1L);

        assertThat(key).matches("customers/1/[0-9a-f\\-]{36}\\.pdf");

        assertThat(keysUnder("customers/1/")).contains(key);

        assertThat(storage.load(key).getInputStream().readAllBytes()).isEqualTo(uploaded);
    }

    @Test
    void store_appliesServerSideEncryptionAes256() {
        String key = storage.store(content("pdf-content-encrypted"), "proof.pdf", 7L);

        HeadObjectResponse head = s3Client.headObject(HeadObjectRequest.builder()
                .bucket(BUCKET)
                .key(key)
                .build());
        assertThat(head.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(head.serverSideEncryptionAsString()).isEqualTo("AES256");

        List<ServerSideEncryptionRule> rules = s3Client.getBucketEncryption(
                        GetBucketEncryptionRequest.builder().bucket(BUCKET).build())
                .serverSideEncryptionConfiguration()
                .rules();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).applyServerSideEncryptionByDefault().sseAlgorithm())
                .isEqualTo(ServerSideEncryption.AES256);
    }

    @Test
    void bucket_blocksAllPublicAccess() {
        PublicAccessBlockConfiguration configuration = s3Client.getPublicAccessBlock(
                        GetPublicAccessBlockRequest.builder().bucket(BUCKET).build())
                .publicAccessBlockConfiguration();

        assertThat(configuration.blockPublicAcls()).isTrue();
        assertThat(configuration.ignorePublicAcls()).isTrue();
        assertThat(configuration.blockPublicPolicy()).isTrue();
        assertThat(configuration.restrictPublicBuckets()).isTrue();
    }

    @Test
    void load_missingObject_returnsReuploadMessage() {
        assertThatThrownBy(() -> storage.load("customers/1/never-stored.pdf"))
                .isInstanceOfSatisfying(DocumentContentUnavailableException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo(REUPLOAD_MESSAGE);
                    assertThat(ex.getStoredFileName()).isEqualTo("customers/1/never-stored.pdf");
                });
    }

    @Test
    void delete_removesObjectSoDownloadReportsMissing() {
        Customer existingCustomer = customer(9L);
        String key = storage.store(content("pdf-content-to-delete"), "proof.pdf", existingCustomer.getId());
        CustomerDocument stored = document(11L, existingCustomer, "proof.pdf", key, 4L);
        CustomerDocumentRepository documentRepository = mock(CustomerDocumentRepository.class);
        DocumentDownloadRepository downloadRepository = mock(DocumentDownloadRepository.class);
        when(documentRepository.findById(11L)).thenReturn(Optional.of(stored));

        DocumentService documentService = new DocumentService(documentRepository, mock(CustomerRepository.class),
                storage, documentProperties(), downloadRepository);

        documentService.delete(existingCustomer.getId(), 11L);

        verify(downloadRepository).deleteByDocument_Id(11L);
        verify(documentRepository).delete(stored);
        assertThat(keysUnder("customers/" + existingCustomer.getId() + "/")).doesNotContain(key);
        assertThatThrownBy(() -> storage.load(key))
                .isInstanceOfSatisfying(DocumentContentUnavailableException.class,
                        ex -> assertThat(ex.getMessage()).isEqualTo(REUPLOAD_MESSAGE));
    }

    @Test
    void download_overS3_keepsOriginalNameTypeAndContent() throws Exception {
        String originalFileName = "kyc proof (final) [übersicht].pdf";
        byte[] uploaded = content("pdf-content-for-download");
        Customer existingCustomer = customer(5L);
        String key = storage.store(uploaded, originalFileName, existingCustomer.getId());
        CustomerDocument stored = document(7L, existingCustomer, originalFileName, key, uploaded.length);
        CustomerDocumentRepository documentRepository = mock(CustomerDocumentRepository.class);
        DocumentDownloadRepository downloadRepository = mock(DocumentDownloadRepository.class);
        when(documentRepository.findById(7L)).thenReturn(Optional.of(stored));

        DocumentDownloadService documentDownloadService =
                new DocumentDownloadService(documentRepository, downloadRepository, storage);

        DocumentDownloadResult result = documentDownloadService.download(existingCustomer.getId(), 7L, "ops.user");

        assertThat(result.originalFileName()).isEqualTo(originalFileName);
        assertThat(result.mediaType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(result.sizeBytes()).isEqualTo(uploaded.length);
        assertThat(result.resource().getInputStream().readAllBytes()).isEqualTo(uploaded);
        verify(downloadRepository).save(any(DocumentDownload.class));
    }
}
