package com.techverito.banking.event;

import java.time.Duration;
import java.time.Instant;

/**
 * The one retry schedule both outbox drains use: the first retry waits
 * {@code initialDelay}, and every further retry waits {@code multiplier} times as long
 * as the one before it – one second, two, four and eight seconds with the defaults – and
 * there is deliberately no cap on the resulting delay.
 *
 * <p>Both values come from configuration, so a deployment makes the schedule more or
 * less patient without a code change, and the arithmetic lives here rather than in each
 * drain, so the two cannot drift apart. The schedule is stateless: it answers the delay
 * after the n-th failed attempt, which is what the attempts counter on an outbox row
 * already holds.</p>
 */
public final class RetrySchedule {

    private RetrySchedule() {
    }

    /**
     * How long to wait after the given failed attempt: {@code initialDelay} after the
     * first failure, then {@code multiplier} times the previous wait after each further
     * one, so the delays grow exponentially.
     *
     * @param failedAttempt the attempt that just failed, counted from 1
     * @param initialDelay  the wait after the first failure
     * @param multiplier    the factor each further wait grows by, 1 or greater
     * @throws IllegalArgumentException when the attempt number, the initial delay or the
     *                                  multiplier cannot describe a retry schedule
     */
    public static Duration delayAfter(int failedAttempt, Duration initialDelay, double multiplier) {
        if (failedAttempt < 1) {
            throw new IllegalArgumentException("A failed attempt is counted from 1, but was " + failedAttempt + ".");
        }
        if (initialDelay == null || initialDelay.isNegative()) {
            throw new IllegalArgumentException("A retry schedule needs a non-negative initial delay.");
        }
        if (multiplier < 1) {
            throw new IllegalArgumentException(
                    "A retry schedule needs a multiplier of 1 or greater, but was " + multiplier + ".");
        }
        double factor = Math.pow(multiplier, failedAttempt - 1);
        double nanos = initialDelay.toNanos() * factor;
        if (nanos >= (double) Long.MAX_VALUE) {
            // Far beyond any attempt limit the configuration allows; saturated so a very
            // long schedule cannot overflow into a wait in the past.
            return Duration.ofNanos(Long.MAX_VALUE);
        }
        return Duration.ofNanos((long) nanos);
    }

    /**
     * When the next attempt of a row may run: its failure time plus {@link #delayAfter}.
     */
    public static Instant nextAttemptAt(Instant failureTime, int failedAttempt, Duration initialDelay,
                                        double multiplier) {
        return failureTime.plus(delayAfter(failedAttempt, initialDelay, multiplier));
    }
}
