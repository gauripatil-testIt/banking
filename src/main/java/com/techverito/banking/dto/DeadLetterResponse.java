package com.techverito.banking.dto;

import com.techverito.banking.entity.DeadLetterEntry;
import com.techverito.banking.entity.DeadLetterStatus;
import com.techverito.banking.event.EventType;

import java.time.Instant;

/**
 * One dead-lettered item as operations reads it: the payload it kept, the id it carries,
 * why it was set aside, how many attempts it took, when it first and last failed, where
 * it came from, and who replayed it and when – so nothing disappears without a trace.
 */
public record DeadLetterResponse(
        Long id,
        String source,
        String sourceId,
        EventType eventType,
        String payload,
        String reason,
        int attempts,
        Instant firstFailedAt,
        Instant lastFailedAt,
        Instant deadLetteredAt,
        DeadLetterStatus status,
        String replayedBy,
        Instant replayedAt,
        int replayCount
) {
    public static DeadLetterResponse from(DeadLetterEntry entry) {
        return new DeadLetterResponse(
                entry.getId(), entry.getSource(), entry.getSourceId(), entry.getEventType(),
                entry.getPayload(), entry.getReason(), entry.getAttempts(),
                entry.getFirstFailedAt(), entry.getLastFailedAt(), entry.getDeadLetteredAt(),
                entry.getStatus(), entry.getReplayedBy(), entry.getReplayedAt(), entry.getReplayCount()
        );
    }
}
