package com.techverito.banking.config;

import com.techverito.banking.event.InMemoryEventBus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Creates the in-memory event bus when {@code banking.events.transport=in-memory}.
 *
 * <p>That transport is for local development and automated tests only, so the bean
 * refuses to be created unless the {@code local} or {@code test} profile is active.
 * A deployment that selected the in-memory bus by mistake fails at startup with a
 * readable message instead of quietly keeping every event inside one JVM.</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "banking.events", name = "transport", havingValue = "in-memory")
public class InMemoryEventBusConfig {

    static final String LOCAL_PROFILE = "local";

    static final String TEST_PROFILE = "test";

    @Bean
    public InMemoryEventBus inMemoryEventBus(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of(LOCAL_PROFILE, TEST_PROFILE))) {
            throw new IllegalStateException("The in-memory event bus is for local development and automated tests "
                    + "only: banking.events.transport=in-memory requires the " + LOCAL_PROFILE + " or "
                    + TEST_PROFILE + " profile to be active. Set banking.events.transport=kafka to publish "
                    + "business events to the real broker.");
        }
        return new InMemoryEventBus();
    }
}
