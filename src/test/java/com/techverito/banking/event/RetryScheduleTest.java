package com.techverito.banking.event;

import com.techverito.banking.event.outbox.OutboxProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The retry schedule both outbox drains share: the delay after each failed attempt, the
 * next attempt time it produces, the values it refuses, and the defaults the configuration
 * binds when nothing is set.
 */
class RetryScheduleTest {

    private static final Duration INITIAL_DELAY = Duration.ofSeconds(1);

    private static final double MULTIPLIER = 2;

    private static final Instant FAILED_AT = Instant.parse("2025-03-04T09:07:45Z");

    @Test
    void delayAfter_growsExponentiallyFromTheInitialDelay() {
        assertThat(RetrySchedule.delayAfter(1, INITIAL_DELAY, MULTIPLIER)).isEqualTo(Duration.ofSeconds(1));
        assertThat(RetrySchedule.delayAfter(2, INITIAL_DELAY, MULTIPLIER)).isEqualTo(Duration.ofSeconds(2));
        assertThat(RetrySchedule.delayAfter(3, INITIAL_DELAY, MULTIPLIER)).isEqualTo(Duration.ofSeconds(4));
        assertThat(RetrySchedule.delayAfter(4, INITIAL_DELAY, MULTIPLIER)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void delayAfter_followsTheConfiguredInitialDelayAndMultiplier() {
        assertThat(RetrySchedule.delayAfter(1, Duration.ofSeconds(5), 3)).isEqualTo(Duration.ofSeconds(5));
        assertThat(RetrySchedule.delayAfter(2, Duration.ofSeconds(5), 3)).isEqualTo(Duration.ofSeconds(15));
        assertThat(RetrySchedule.delayAfter(3, Duration.ofSeconds(5), 3)).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void delayAfter_aMultiplierOfOneKeepsEveryDelayTheSame() {
        assertThat(RetrySchedule.delayAfter(4, Duration.ofSeconds(2), 1)).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void delayAfter_rejectsAnAttemptNumberBelowOne() {
        assertThatThrownBy(() -> RetrySchedule.delayAfter(0, INITIAL_DELAY, MULTIPLIER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delayAfter_rejectsAMissingOrNegativeInitialDelay() {
        assertThatThrownBy(() -> RetrySchedule.delayAfter(1, null, MULTIPLIER))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RetrySchedule.delayAfter(1, Duration.ofSeconds(-1), MULTIPLIER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delayAfter_rejectsAMultiplierBelowOne() {
        assertThatThrownBy(() -> RetrySchedule.delayAfter(1, INITIAL_DELAY, 0.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nextAttemptAt_addsThatAttemptsDelayToTheFailureTime() {
        assertThat(RetrySchedule.nextAttemptAt(FAILED_AT, 1, INITIAL_DELAY, MULTIPLIER))
                .isEqualTo(FAILED_AT.plusSeconds(1));
        assertThat(RetrySchedule.nextAttemptAt(FAILED_AT, 3, INITIAL_DELAY, MULTIPLIER))
                .isEqualTo(FAILED_AT.plusSeconds(4));
    }

    @Test
    void outboxProperties_bindTheDocumentedRetryDefaultsWhenNothingIsSet() {
        new ApplicationContextRunner()
                .withUserConfiguration(OutboxDefaultsConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    OutboxProperties properties = context.getBean(OutboxProperties.class);
                    assertThat(properties.maxAttempts()).isEqualTo(5);
                    assertThat(properties.retryInitialDelay()).isEqualTo(Duration.ofSeconds(1));
                    assertThat(properties.retryMultiplier()).isEqualTo(2);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxProperties.class)
    static class OutboxDefaultsConfig {
    }
}
