package com.techverito.banking.event;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the one-seam rule: controllers and services publish and subscribe through
 * {@link BusinessEventBus} only, so no broker library may appear in their source. The
 * check reads the source files rather than loading them, because the production code
 * must never be scanned at runtime.
 */
class BusinessEventBusIsolationTest {

    private static final List<String> BUSINESS_CODE_PACKAGES = List.of(
            "src/main/java/com/techverito/banking/controller",
            "src/main/java/com/techverito/banking/service");

    private static final List<String> FORBIDDEN_BROKER_PACKAGES = List.of(
            "org.springframework.kafka",
            "org.springframework.amqp",
            "com.rabbitmq");

    @Test
    void controllerAndServicePackagesDoNotMentionBrokerClasses() {
        List<Path> sources = businessCodeSources();

        List<String> offenders = new ArrayList<>();
        for (Path source : sources) {
            String content = read(source);
            for (String brokerPackage : FORBIDDEN_BROKER_PACKAGES) {
                if (content.contains(brokerPackage)) {
                    offenders.add(source + " mentions " + brokerPackage);
                }
            }
        }

        assertThat(offenders)
                .as("business code must publish and subscribe through BusinessEventBus only")
                .isEmpty();
    }

    private List<Path> businessCodeSources() {
        List<Path> sources = new ArrayList<>();
        for (String packageDir : BUSINESS_CODE_PACKAGES) {
            Path root = Path.of(packageDir);
            assertThat(Files.isDirectory(root))
                    .as("%s must exist, or the isolation check passes without checking anything", packageDir)
                    .isTrue();
            try (Stream<Path> files = Files.walk(root)) {
                sources.addAll(files.filter(Files::isRegularFile).toList());
            } catch (IOException ex) {
                throw new UncheckedIOException("Could not scan " + packageDir + " for broker classes", ex);
            }
        }
        assertThat(sources).isNotEmpty();
        return sources;
    }

    private String read(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read " + source, ex);
        }
    }
}
