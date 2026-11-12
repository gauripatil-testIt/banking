package com.techverito.banking.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfig.class);

    @Test
    void defaults_bindToTenMbAndPdfJpgPng() {
        contextRunner.run(context -> {
            DocumentProperties properties = context.getBean(DocumentProperties.class);

            assertThat(properties.maxFileSize()).isEqualTo(DataSize.ofMegabytes(10));
            assertThat(properties.allowedExtensions()).containsExactlyInAnyOrder("pdf", "jpg", "png");
            assertThat(properties.storageDir().normalize()).isEqualTo(Path.of("./uploads/documents").normalize());
        });
    }

    @Test
    void overrides_bindFromPropertySource() {
        contextRunner
                .withPropertyValues(
                        "banking.documents.storage-dir=/var/banking/documents",
                        "banking.documents.allowed-extensions=pdf,jpeg",
                        "banking.documents.max-file-size=2MB")
                .run(context -> {
                    DocumentProperties properties = context.getBean(DocumentProperties.class);

                    assertThat(properties.storageDir().toString()).isEqualTo(Path.of("/var/banking/documents").toString());
                    assertThat(properties.allowedExtensions()).containsExactlyInAnyOrder("pdf", "jpeg");
                    assertThat(properties.maxFileSize()).isEqualTo(DataSize.ofMegabytes(2));
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DocumentProperties.class)
    static class PropertiesConfig {
    }
}
