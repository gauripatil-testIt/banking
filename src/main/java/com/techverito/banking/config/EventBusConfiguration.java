package com.techverito.banking.config;

import com.techverito.banking.event.EventBus;
import com.techverito.banking.event.EventRequestContext;
import com.techverito.banking.event.InMemoryEventBus;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the {@link EventBus} implementation from {@code banking.events.bus}.
 * Business code only ever sees the interface, so adding a broker adapter is a
 * new branch here and nothing else.
 */
@Configuration
@EnableConfigurationProperties(EventBusProperties.class)
public class EventBusConfiguration {

    static final String IN_MEMORY = "in-memory";

    @Bean
    public EventBus eventBus(EventBusProperties properties, EventRequestContext requestContext) {
        String configured = (properties.bus() != null) ? properties.bus().trim() : IN_MEMORY;
        if (IN_MEMORY.equalsIgnoreCase(configured)) {
            return new InMemoryEventBus(requestContext);
        }
        throw new IllegalStateException("Unsupported banking.events.bus value: '" + properties.bus()
                + "'. The only supported value is '" + IN_MEMORY + "'.");
    }
}
