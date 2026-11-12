package com.techverito.banking.config;

import com.techverito.banking.storage.LocalFileSystemDocumentStorage;
import com.techverito.banking.storage.S3DocumentStorage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves which storage backend a configuration selects and that a missing S3
 * configuration fails startup with a readable message. No AWS access, no
 * credentials and no network are involved: the S3 client is only ever built on
 * the paths that are expected to fail before it is constructed.
 */
class S3StorageConfigurationTest {

    private static final String STORAGE_DIR_PROPERTY =
            "banking.documents.storage-dir=./target/test-documents/s3-config";

    private final ApplicationContextRunner backendRunner = new ApplicationContextRunner()
            .withUserConfiguration(LocalFileSystemDocumentStorage.class, S3DocumentStorage.class,
                    StorageTestConfig.class)
            .withPropertyValues(STORAGE_DIR_PROPERTY);

    private final ApplicationContextRunner s3ClientRunner = new ApplicationContextRunner()
            .withUserConfiguration(S3ClientConfig.class, S3PropertiesConfig.class);

    @Test
    void s3IsDefaultBackend() {
        backendRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(S3DocumentStorage.class);
            assertThat(context).doesNotHaveBean(LocalFileSystemDocumentStorage.class);
        });
    }

    @Test
    void s3IsSelectedByName() {
        backendRunner.withPropertyValues("banking.documents.storage=s3").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(S3DocumentStorage.class);
            assertThat(context).doesNotHaveBean(LocalFileSystemDocumentStorage.class);
        });
    }

    @Test
    void localStorageBackendIsSelectable() {
        backendRunner.withPropertyValues("banking.documents.storage=local").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LocalFileSystemDocumentStorage.class);
            assertThat(context).doesNotHaveBean(S3DocumentStorage.class);
        });
    }

    @Test
    void missingBucketAndRegionFailsStartupWithReadableMessage() {
        s3ClientRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("banking.documents.s3.bucket")
                    .hasStackTraceContaining("banking.documents.s3.region")
                    .hasStackTraceContaining("banking.documents.storage=local");
        });
    }

    @Test
    void blankBucketFailsStartupWithReadableMessage() {
        s3ClientRunner
                .withPropertyValues("banking.documents.storage=s3", "banking.documents.s3.region=eu-west-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("banking.documents.s3.bucket")
                            .hasStackTraceContaining("bucket=not set")
                            .hasStackTraceContaining("banking.documents.storage=local");
                });
    }

    @Test
    void blankRegionFailsStartupWithReadableMessage() {
        s3ClientRunner
                .withPropertyValues("banking.documents.storage=s3", "banking.documents.s3.bucket=bank-test-bucket")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("banking.documents.s3.region")
                            .hasStackTraceContaining("region=not set")
                            .hasStackTraceContaining("banking.documents.storage=local");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DocumentProperties.class)
    static class StorageTestConfig {

        @Bean
        S3Client s3Client() {
            return mock(S3Client.class);
        }

        @Bean
        S3Properties s3Properties() {
            return new S3Properties("bank-test-bucket", "us-east-1", null);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(S3Properties.class)
    static class S3PropertiesConfig {
    }
}
