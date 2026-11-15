package com.techverito.banking.event;

import com.techverito.banking.config.EventProperties;
import com.techverito.banking.config.InMemoryEventBusConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The in-memory bus: publish and subscribe with no broker, no Docker and no network.
 *
 * <p>It also proves the transport is usable without a broker installed when the
 * test profile is active, and that the same transport refuses to start outside the
 * local and test profiles.</p>
 */
class InMemoryEventBusTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final InMemoryEventBus bus = new InMemoryEventBus();

    private BusinessEvent event(EventType type) {
        return BusinessEvent.create(type, 1L, null, "ops.user", "corr-123", CLOCK);
    }

    private ApplicationContextRunner inMemoryRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(InMemoryEventBusConfig.class, EventPropertiesConfig.class)
                .withPropertyValues("banking.events.transport=in-memory");
    }

    @Test
    void publish_deliversToEveryHandlerSubscribedToTheTypeInSubscriptionOrder() {
        List<String> handlers = new ArrayList<>();
        List<String> eventIds = new ArrayList<>();
        bus.subscribe(EventType.ACCOUNT_OPENED, event -> {
            handlers.add("first");
            eventIds.add(event.eventId());
        });
        bus.subscribe(EventType.ACCOUNT_OPENED, event -> handlers.add("second"));

        BusinessEvent published = event(EventType.ACCOUNT_OPENED);
        bus.publish(published);

        assertThat(handlers).containsExactly("first", "second");
        assertThat(eventIds).containsExactly(published.eventId());
    }

    @Test
    void publish_reachesNoHandlerSubscribedToAnotherType() {
        List<BusinessEvent> accountOpened = new ArrayList<>();
        List<BusinessEvent> documentUploaded = new ArrayList<>();
        bus.subscribe(EventType.ACCOUNT_OPENED, accountOpened::add);
        bus.subscribe(EventType.DOCUMENT_UPLOADED, documentUploaded::add);

        BusinessEvent published = event(EventType.ACCOUNT_OPENED);
        bus.publish(published);

        assertThat(accountOpened).containsExactly(published);
        assertThat(accountOpened.get(0).eventId()).isEqualTo(published.eventId());
        assertThat(documentUploaded).isEmpty();
    }

    @Test
    void publish_deliversSynchronouslyOnThePublishingThread() {
        String callerThread = Thread.currentThread().getName();
        List<String> handlerThreads = new ArrayList<>();
        bus.subscribe(EventType.EXPORT_REQUESTED, event -> handlerThreads.add(Thread.currentThread().getName()));

        bus.publish(event(EventType.EXPORT_REQUESTED));

        assertThat(handlerThreads).containsExactly(callerThread);
    }

    @Test
    void publish_withNoSubscribers_doesNothing() {
        assertThatCode(() -> bus.publish(event(EventType.STATEMENT_GENERATED))).doesNotThrowAnyException();
    }

    @Test
    void inMemoryTransport_providesAPublishAndSubscribeBusWithoutABrokerUnderTheTestProfile() {
        inMemoryRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InMemoryEventBus.class);

                    BusinessEventBus eventBus = context.getBean(BusinessEventBus.class);
                    List<BusinessEvent> received = new ArrayList<>();
                    eventBus.subscribe(EventType.STATEMENT_GENERATED, received::add);

                    BusinessEvent published = event(EventType.STATEMENT_GENERATED);
                    eventBus.publish(published);

                    assertThat(received).containsExactly(published);
                    assertThat(received.get(0).eventId()).isEqualTo(published.eventId());
                });
    }

    @Test
    void inMemoryTransport_isUsableUnderTheLocalProfileToo() {
        inMemoryRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("local"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(InMemoryEventBus.class);
                });
    }

    @Test
    void inMemoryTransport_failsOutsideLocalAndTestProfilesNamingBothProfilesAndTheProperty() {
        inMemoryRunner().run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("local")
                    .hasStackTraceContaining("test")
                    .hasStackTraceContaining("banking.events.transport");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(EventProperties.class)
    static class EventPropertiesConfig {
    }
}
