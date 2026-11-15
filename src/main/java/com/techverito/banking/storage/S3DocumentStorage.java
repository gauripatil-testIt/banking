package com.techverito.banking.storage;

import com.techverito.banking.config.S3Properties;
import com.techverito.banking.exception.DocumentContentUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Stores customer documents in the bank's S3 bucket, one key per document under
 * {@code customers/{customerId}/}. Objects are encrypted at rest with SSE-S3 and
 * are never made public: downloads are streamed back through the application, so
 * no presigned URL is generated anywhere.
 */
@Component
@ConditionalOnProperty(prefix = "banking.documents", name = "storage", havingValue = "s3", matchIfMissing = true)
public class S3DocumentStorage implements DocumentStorage {

    public static final String STORE_FAILURE_MESSAGE =
            "The document could not be stored. Please try again.";

    private static final String DELETE_FAILURE_MESSAGE =
            "The document could not be deleted. Please try again.";

    private static final String KEY_PREFIX = "customers/";

    private static final String ORIGINAL_FILE_NAME_METADATA = "original-file-name";

    private static final int NOT_FOUND = 404;

    private static final Logger log = LoggerFactory.getLogger(S3DocumentStorage.class);

    private final S3Client s3Client;
    private final S3Properties properties;

    public S3DocumentStorage(S3Client s3Client, S3Properties properties) {
        this.s3Client = s3Client;
        this.properties = properties;
    }

    @Override
    public String store(byte[] content, String originalFileName, Long customerId) {
        String key = objectKey(customerId, originalFileName);
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .metadata(Map.of(ORIGINAL_FILE_NAME_METADATA, metadataValue(originalFileName)))
                .build();
        try {
            s3Client.putObject(request, RequestBody.fromBytes(content));
        } catch (SdkException ex) {
            log.error("Could not store document object in S3: bucket={}, key={}", properties.bucket(), key, ex);
            throw new DocumentStorageException(STORE_FAILURE_MESSAGE, ex);
        }
        return key;
    }

    @Override
    public Resource load(String storedFileName) {
        try {
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(GetObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(storedFileName)
                    .build());
            return new InputStreamResource(response);
        } catch (NoSuchKeyException ex) {
            throw new DocumentContentUnavailableException(storedFileName);
        } catch (S3Exception ex) {
            if (ex.statusCode() == NOT_FOUND) {
                throw new DocumentContentUnavailableException(storedFileName);
            }
            throw new DocumentContentUnavailableException(storedFileName, ex);
        } catch (SdkException ex) {
            throw new DocumentContentUnavailableException(storedFileName, ex);
        }
    }

    @Override
    public void delete(String storedFileName) {
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(storedFileName)
                    .build());
        } catch (NoSuchKeyException ex) {
            // Deleting something that is already gone is not an error for this port.
        } catch (S3Exception ex) {
            if (ex.statusCode() != NOT_FOUND) {
                log.error("Could not delete document object from S3: bucket={}, key={}",
                        properties.bucket(), storedFileName, ex);
                throw new DocumentStorageException(DELETE_FAILURE_MESSAGE, ex);
            }
        } catch (SdkException ex) {
            log.error("Could not delete document object from S3: bucket={}, key={}",
                    properties.bucket(), storedFileName, ex);
            throw new DocumentStorageException(DELETE_FAILURE_MESSAGE, ex);
        }
    }

    private String objectKey(Long customerId, String originalFileName) {
        String extension = extensionOf(originalFileName);
        String name = extension.isEmpty()
                ? UUID.randomUUID().toString()
                : UUID.randomUUID() + "." + extension;
        return KEY_PREFIX + customerId + "/" + name;
    }

    /**
     * S3 user metadata has to be US-ASCII, while a document may be uploaded under
     * a name with spaces, brackets or non-English characters, so the name is
     * percent-encoded rather than dropped or sent as-is.
     */
    private String metadataValue(String originalFileName) {
        return (originalFileName == null) ? "" : URLEncoder.encode(originalFileName, StandardCharsets.UTF_8);
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
}
