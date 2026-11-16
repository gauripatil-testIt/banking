package com.techverito.banking.config;

import com.techverito.banking.event.BusinessEventBus;
import com.techverito.banking.event.BusinessEventJson;
import com.techverito.banking.event.InMemoryBusinessEventBus;
import com.techverito.banking.event.kafka.KafkaBusinessEventBus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Proves which business event bus a configuration selects, that the properties are
 * bound, and that a Kafka bus without a broker address fails startup with a readable
 * message. No broker, no Docker and no network are involved: the Kafka template is a
 * mock and the only producer configuration exercised is the one that is expected to
 * fail before it is built.
 */
class EventBusConfigurationTest {

    private final ApplicationContextRunner busRunner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaBusinessEventBus.class, InMemoryBusinessEventBus.class,
                    BusinessEventJson.class, EventPropertiesConfig.class, MockKafkaTemplateConfig.class);

    private final ApplicationContextRunner producerRunner = new ApplicationContextRunner()
            .withUserConfiguration(KafkaProducerConfig.class, KafkaBusinessEventBus.class, BusinessEventJson.class,
                    EventPropertiesConfig.class);

    @Test
    void kafkaIsTheDefaultImplementation() {
        busRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BusinessEventBus.class);
            assertThat(context).getBean(BusinessEventBus.class).isInstanceOf(KafkaBusinessEventBus.class);
            assertThat(context).doesNotHaveBean(InMemoryBusinessEventBus.class);
        });
    }

    @Test
    void inMemoryImplementationIsSelectedByProperty() {
        busRunner.withPropertyValues("banking.events.bus=in-memory").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BusinessEventBus.class);
            assertThat(context).getBean(BusinessEventBus.class).isInstanceOf(InMemoryBusinessEventBus.class);
            assertThat(context).doesNotHaveBean(KafkaBusinessEventBus.class);
        });
    }

    @Test
    void missingBootstrapServersFailsStartupWithReadableMessage() {
        producerRunner.withPropertyValues("banking.events.bus=kafka").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("banking.events.kafka.bootstrap-servers")
                    .hasStackTraceContaining("banking.events.bus=in-memory");
        });
    }

    @Test
    void bindsTopicAndBootstrapServers() {
        busRunner.run(context -> {
            assertThat(context).hasNotFailed();

            EventBusProperties properties = context.getBean(EventBusProperties.class);
            assertThat(properties.bus()).isEqualTo(EventBusType.KAFKA);
            assertThat(properties.topic()).isEqualTo("banking.events");

            KafkaEventProperties kafkaProperties = context.getBean(KafkaEventProperties.class);
            assertThat(kafkaProperties.bootstrapServers()).isNull();
            assertThat(kafkaProperties.clientId()).isEqualTo("banking-service");
        });

        busRunner.withPropertyValues("banking.events.bus=in-memory",
                        "banking.events.topic=banking.events.custom",
                        "banking.events.kafka.bootstrap-servers=localhost:9092",
                        "banking.events.kafka.client-id=banking-test")
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    EventBusProperties properties = context.getBean(EventBusProperties.class);
                    assertThat(properties.bus()).isEqualTo(EventBusType.IN_MEMORY);
                    assertThat(properties.topic()).isEqualTo("banking.events.custom");

                    KafkaEventProperties kafkaProperties = context.getBean(KafkaEventProperties.class);
                    assertThat(kafkaProperties.bootstrapServers()).isEqualTo("localhost:9092");
                    assertThat(kafkaProperties.clientId()).isEqualTo("banking-test");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({EventBusProperties.class, KafkaEventProperties.class})
    static class EventPropertiesConfig {
    }

    @Configuration(proxyBeanMethods = false)
    static class MockKafkaTemplateConfig {

        @Bean
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate() {
            return mock(KafkaTemplate.class);
        }
    }
}
