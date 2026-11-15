package com.techverito.banking.event;

/**
 * Where an event that could not be published is reported, so it can be investigated
 * instead of quietly disappearing.
 *
 * <p>Like {@link BusinessEventBus}, this interface names no broker: the outbox drain
 * reports a failure through it without knowing whether the dead-letter destination is
 * a Kafka topic or the application log. The stored event itself is kept separately by
 * the caller; this only announces the failure with its reason.</p>
 */
public interface DeadLetterPublisher {

    /**
     * Reports an event that could not be published, with the reason it could not be.
     */
    void deadLetter(BusinessEvent event, String reason);
}
