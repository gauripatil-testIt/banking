package com.techverito.banking.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

/**
 * Builds the S3 client used for customer documents. Active unless
 * {@code banking.documents.storage=local}, so a deployment that forgot its S3
 * settings fails here at startup with a readable message instead of silently
 * writing documents to the application server's disk.
 *
 * No credentials provider is configured: the SDK default provider chain supplies
 * the IAM role inside AWS and the developer's local AWS profile on a workstation,
 * so the same code runs in both places.
 */
@Configuration
@ConditionalOnProperty(prefix = "banking.documents", name = "storage", havingValue = "s3", matchIfMissing = true)
public class S3ClientConfig {

    @Bean
    public S3Client s3Client(S3Properties properties) {
        String bucket = properties.bucket();
        String region = properties.region();
        if (isBlank(bucket) || isBlank(region)) {
            throw new IllegalStateException(missingSettingsMessage(bucket, region));
        }
        S3ClientBuilder builder = S3Client.builder().region(Region.of(region.trim()));
        if (properties.endpoint() != null) {
            // An S3-compatible endpoint (LocalStack) is addressed path-style:
            // http://host:port/<bucket>/<key>. Real AWS keeps its own addressing.
            builder.endpointOverride(properties.endpoint()).forcePathStyle(true);
        }
        return builder.build();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String missingSettingsMessage(String bucket, String region) {
        return "Document storage is set to S3, but it is not configured: "
                + "banking.documents.s3.bucket and banking.documents.s3.region must both be set from the environment"
                + " (bucket=" + (isBlank(bucket) ? "not set" : bucket) + ", region="
                + (isBlank(region) ? "not set" : region) + "). Set banking.documents.storage=local to store "
                + "documents on the application server's disk instead.";
    }
}
