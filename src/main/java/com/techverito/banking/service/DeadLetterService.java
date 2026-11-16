package com.techverito.banking.service;

import com.techverito.banking.entity.BusinessEventOutboxEntry;
import com.techverito.banking.entity.DeadLetterEntry;
import com.techverito.banking.entity.DeadLetterStatus;
import com.techverito.banking.repository.DeadLetterItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Records an item that kept failing in the dead-letter store, so nothing disappears
 * without a trace: the stored payload, the id it carries, the reason it was given up
 * on, how many attempts it took, when it first and last failed, and where it came from.
 *
 * <p>The store is the database table alone – there is no broker-level dead-letter queue
 * beside it – and a repeated exhaustion of the same item is a no-op rather than a second
 * row. No broker type appears here: this service is about the record, not the transport
 * that could not carry it.</p>
 */
@Service
public class DeadLetterService {

    /** The origin label of an item that came from the business-event outbox. */
    public static final String SOURCE_BUSINESS_EVENT = "business-event-outbox";

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private final DeadLetterItemRepository deadLetterItemRepository;

    public DeadLetterService(DeadLetterItemRepository deadLetterItemRepository) {
        this.deadLetterItemRepository = deadLetterItemRepository;
    }

    /**
     * Copies an outbox row whose attempts are exhausted into the dead-letter store and
     * answers the stored item. The row is returned as it stands when this event has
     * already been set aside, so draining twice cannot store a second copy of it.
     */
    @Transactional
    public DeadLetterEntry recordBusinessEventFailure(BusinessEventOutboxEntry entry) {
        DeadLetterEntry alreadyRecorded = deadLetterItemRepository
                .findFirstBySourceAndSourceId(SOURCE_BUSINESS_EVENT, entry.getEventId())
                .orElse(null);
        if (alreadyRecorded != null) {
            return alreadyRecorded;
        }

        Instant deadLetteredAt = firstNonNull(entry.getDeadLetteredAt(), entry.getLastFailedAt());
        DeadLetterEntry item = DeadLetterEntry.builder()
                .source(SOURCE_BUSINESS_EVENT)
                .sourceId(entry.getEventId())
                .eventType(entry.getEventType())
                .payload(entry.getPayload())
                .reason(entry.getLastError())
                .attempts(entry.getAttempts())
                .firstFailedAt(firstNonNull(entry.getFirstFailedAt(), deadLetteredAt))
                .lastFailedAt(firstNonNull(entry.getLastFailedAt(), deadLetteredAt))
                .deadLetteredAt(deadLetteredAt)
                .status(DeadLetterStatus.PENDING)
                .replayCount(0)
                .build();
        DeadLetterEntry stored = deadLetterItemRepository.save(item);
        log.debug("Recorded a dead-lettered business event: sourceId={}, type={}, attempts={}",
                stored.getSourceId(), stored.getEventType(), stored.getAttempts());
        return stored;
    }

    private Instant firstNonNull(Instant first, Instant second) {
        return (first != null) ? first : second;
    }
}
