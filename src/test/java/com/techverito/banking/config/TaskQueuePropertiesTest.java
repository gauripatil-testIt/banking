package com.techverito.banking.config;

import com.techverito.banking.entity.BackgroundTaskType;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.TopicExchange;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exchange, the four queue names and the four routing keys are configuration under
 * {@code banking.tasks.*}: the documented values are the defaults, and an environment can
 * override each of them without a code change.
 */
class TaskQueuePropertiesTest {

    private static TaskQueueProperties properties(String exchange, String welcomeKey, String statementKey,
                                                 String exportKey, String scanKey) {
        return new TaskQueueProperties(exchange, "q.welcome", "q.statement", "q.export", "q.scan",
                welcomeKey, statementKey, exportKey, scanKey, Duration.ofSeconds(5), false);
    }

    @Test
    void anOverriddenRoutingKeyIsTheKeyTheTopologyBinds() {
        TaskQueueProperties overridden = properties("banking.tasks.other", "rk.welcome", "rk.statement",
                "rk.export", "rk.scan");

        assertThat(overridden.routingKey(BackgroundTaskType.WELCOME_EMAIL)).isEqualTo("rk.welcome");
        assertThat(overridden.routingKey(BackgroundTaskType.STATEMENT_GENERATION)).isEqualTo("rk.statement");
        assertThat(overridden.routingKey(BackgroundTaskType.DOCUMENT_EXPORT)).isEqualTo("rk.export");
        assertThat(overridden.routingKey(BackgroundTaskType.DOCUMENT_SCAN)).isEqualTo("rk.scan");

        var declarables = new RabbitTaskConfig().taskTopology(overridden).getDeclarables();

        assertThat(declarables.stream().filter(TopicExchange.class::isInstance)
                .map(TopicExchange.class::cast)
                .map(TopicExchange::getName))
                .containsExactly("banking.tasks.other");

        Map<String, String> queueByRoutingKey = declarables.stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .collect(Collectors.toMap(Binding::getRoutingKey, Binding::getDestination));
        assertThat(queueByRoutingKey).containsExactlyInAnyOrderEntriesOf(Map.of(
                "rk.welcome", "q.welcome",
                "rk.statement", "q.statement",
                "rk.export", "q.export",
                "rk.scan", "q.scan"));
    }
}
