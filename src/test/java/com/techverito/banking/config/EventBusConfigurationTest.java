package com.techverito.banking.config;

import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.InMemoryEventBus;
import com.techverito.banking.event.kafka.KafkaEventBus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves which event bus a configuration selects and that the in-memory transport
 * is only accepted for local development and automated tests. No broker, no Docker
 * and no network are involved: the Kafka adapter is given a mocked template, so
 * every assertion here is about bean selection rather than about connecting.
 *
 * <p>Which implementation is used is a property (and, for the in-memory bus, a
 * profile) decision only – no business code changes and no test-only bean
 * definition substitutes one implementation for the other.</p>
 */
class EventBusConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaEventBus.class, InMemoryEventBusConfig.class,
                    BusinessEventBusTestConfig.class);

    private ApplicationContextRunner withProfile(String profile) {
        return contextRunner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile));
    }

    @Test
    void kafkaTransport_isSelectedWhenNoTransportPropertyIsSet() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BusinessEventBus.class);
            assertThat(context.getBean(BusinessEventBus.class)).isInstanceOf(KafkaEventBus.class);
            assertThat(context).doesNotHaveBean(InMemoryEventBus.class);
        });
    }

    @Test
    void kafkaTransport_isSelectedByName() {
        contextRunner.withPropertyValues("banking.events.transport=kafka").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BusinessEventBus.class);
            assertThat(context.getBean(BusinessEventBus.class)).isInstanceOf(KafkaEventBus.class);
            assertThat(context).doesNotHaveBean(InMemoryEventBus.class);
        });
    }

    @Test
    void inMemoryTransport_isSelectedUnderTheLocalProfile() {
        contextRunner.withPropertyValues("banking.events.transport=in-memory")
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BusinessEventBus.class);
                    assertThat(context.getBean(BusinessEventBus.class)).isInstanceOf(InMemoryEventBus.class);
                    assertThat(context).doesNotHaveBean(KafkaEventBus.class);
                });
    }

    @Test
    void inMemoryTransport_isSelectedUnderTheTestProfile() {
        withProfile("test")
                .withPropertyValues("banking.events.transport=in-memory")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BusinessEventBus.class);
                    assertThat(context.getBean(BusinessEventBus.class)).isInstanceOf(InMemoryEventBus.class);
                    assertThat(context).doesNotHaveBean(KafkaEventBus.class);
                });
    }

    @Test
    void inMemoryTransport_failsOutsideLocalAndTestProfilesNamingBothProfilesAndTheProperty() {
        contextRunner.withPropertyValues("banking.events.transport=in-memory").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("local")
                    .hasStackTraceContaining("test")
                    .hasStackTraceContaining("banking.events.transport");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EventProperties.class)
    static class BusinessEventBusTestConfig {

        /** A mocked producer, so selecting the Kafka adapter needs no broker. */
        @Bean
        KafkaTemplate<String, BusinessEvent> businessEventBusTestTemplate() {
            return mock(KafkaTemplate.class);
        }
    }
}
