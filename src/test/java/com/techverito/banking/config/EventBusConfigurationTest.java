package com.techverito.banking.config;

import com.techverito.banking.event.EventBus;
import com.techverito.banking.event.EventRequestContext;
import com.techverito.banking.event.InMemoryEventBus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the {@link EventBus} implementation is chosen by configuration only: the
 * default and the explicit {@code in-memory} value give the in-memory bus, and an
 * unsupported value stops the application from starting instead of silently
 * falling back.
 */
class EventBusConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(EventBusConfiguration.class, RequestContextConfig.class);

    @Test
    void defaultConfiguration_selectsInMemoryBus() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(EventBus.class)).isInstanceOf(InMemoryEventBus.class);
        });
    }

    @Test
    void inMemoryValue_selectsInMemoryBus() {
        contextRunner
                .withPropertyValues("banking.events.bus=in-memory")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(EventBus.class)).isInstanceOf(InMemoryEventBus.class);
                });
    }

    @Test
    void unsupportedValue_failsStartupNamingTheValue() {
        contextRunner
                .withPropertyValues("banking.events.bus=rabbit")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context.getStartupFailure())).contains("rabbit");
                });
    }

    private String rootCauseMessage(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }

    @Configuration(proxyBeanMethods = false)
    static class RequestContextConfig {

        @Bean
        EventRequestContext eventRequestContext() {
            return new EventRequestContext();
        }
    }
}
