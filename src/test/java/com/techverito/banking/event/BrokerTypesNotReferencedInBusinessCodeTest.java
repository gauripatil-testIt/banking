package com.techverito.banking.event;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps acceptance criterion 3 true as later stories wire the business flows to the
 * event bus: business code talks to {@link BusinessEventBus} only, so no controller
 * or service may refer to a broker class, and the interface itself carries no
 * broker type.
 */
class BrokerTypesNotReferencedInBusinessCodeTest {

    private static final List<Path> BUSINESS_CODE_PACKAGES = List.of(
            Path.of("src/main/java/com/techverito/banking/controller"),
            Path.of("src/main/java/com/techverito/banking/service"));

    private static final List<String> BROKER_PACKAGES = List.of(
            "org.springframework.kafka",
            "org.apache.kafka",
            "org.springframework.amqp",
            "com.rabbitmq");

    private static final List<String> RABBIT_PACKAGES = List.of(
            "org.springframework.amqp",
            "com.rabbitmq");

    private static final List<String> BROKER_PACKAGE_FRAGMENTS = List.of(
            "kafka",
            "amqp",
            "rabbitmq");

    private static List<Path> javaFilesIn(Path directory) throws IOException {
        assertThat(directory).as("business code package").isDirectory();
        try (Stream<Path> files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }

    @Test
    void businessCode_refersToNoBrokerClass() throws IOException {
        for (Path directory : BUSINESS_CODE_PACKAGES) {
            for (Path file : javaFilesIn(directory)) {
                String source = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                for (String brokerPackage : BROKER_PACKAGES) {
                    assertThat(source)
                            .as("%s must not mention %s", file, brokerPackage)
                            .doesNotContain(brokerPackage.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    @Test
    void businessCodeNamesNoRabbitType() throws IOException {
        for (Path directory : BUSINESS_CODE_PACKAGES) {
            for (Path file : javaFilesIn(directory)) {
                String source = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                for (String rabbitPackage : RABBIT_PACKAGES) {
                    assertThat(source)
                            .as("%s must not mention %s", file, rabbitPackage)
                            .doesNotContain(rabbitPackage.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    @Test
    void eventBusInterface_declaresPublishAndSubscribeOnly() {
        List<String> methodNames = Arrays.stream(BusinessEventBus.class.getDeclaredMethods())
                .map(Method::getName)
                .toList();

        assertThat(methodNames).containsExactlyInAnyOrder("publish", "subscribe");
    }

    @Test
    void eventBusInterface_usesNoBrokerTypeInAnySignature() {
        for (Method method : BusinessEventBus.class.getDeclaredMethods()) {
            List<Class<?>> types = new ArrayList<>();
            types.add(method.getReturnType());
            types.addAll(Arrays.asList(method.getParameterTypes()));
            for (Class<?> type : types) {
                String packageName = type.getPackageName().toLowerCase(Locale.ROOT);
                for (String fragment : BROKER_PACKAGE_FRAGMENTS) {
                    assertThat(packageName)
                            .as("%s must not use a broker type (%s)", method.getName(), type.getName())
                            .doesNotContain(fragment);
                }
            }
        }
    }
}
