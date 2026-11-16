package com.techverito.banking.entity;

import com.techverito.banking.event.EventType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

/**
 * One item that was set aside because it kept failing: the stored payload, the id it
 * carries, the reason it was given up on, how many attempts it took, when it first and
 * last failed, and where it came from. The row is never deleted, so nothing disappears
 * without a trace, and it is the single place a dead-lettered item lives – there is no
 * broker-level dead-letter queue beside it.
 *
 * <p>The unique {@code (source, source_id)} pair is what keeps a repeated exhaustion from
 * storing a second copy of the same item.</p>
 */
@Entity
@Table(name = "dead_letter_items")
public class DeadLetterEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String source;

    @Column(name = "source_id", nullable = false, length = 36)
    private String sourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private EventType eventType;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(length = 1000)
    private String reason;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "first_failed_at", nullable = false)
    private Instant firstFailedAt;

    @Column(name = "last_failed_at", nullable = false)
    private Instant lastFailedAt;

    @Column(name = "dead_lettered_at", nullable = false)
    private Instant deadLetteredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeadLetterStatus status;

    @Column(name = "replay_count", nullable = false)
    private int replayCount;

    @Column(name = "replayed_by", length = 255)
    private String replayedBy;

    @Column(name = "replayed_at")
    private Instant replayedAt;

    public DeadLetterEntry() {}

    private DeadLetterEntry(Builder b) {
        this.id = b.id;
        this.source = b.source;
        this.sourceId = b.sourceId;
        this.eventType = b.eventType;
        this.payload = b.payload;
        this.reason = b.reason;
        this.attempts = b.attempts;
        this.firstFailedAt = b.firstFailedAt;
        this.lastFailedAt = b.lastFailedAt;
        this.deadLetteredAt = b.deadLetteredAt;
        this.status = b.status;
        this.replayCount = b.replayCount;
        this.replayedBy = b.replayedBy;
        this.replayedAt = b.replayedAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private String source;
        private String sourceId;
        private EventType eventType;
        private String payload;
        private String reason;
        private int attempts;
        private Instant firstFailedAt;
        private Instant lastFailedAt;
        private Instant deadLetteredAt;
        private DeadLetterStatus status;
        private int replayCount;
        private String replayedBy;
        private Instant replayedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder source(String v) { this.source = v; return this; }
        public Builder sourceId(String v) { this.sourceId = v; return this; }
        public Builder eventType(EventType v) { this.eventType = v; return this; }
        public Builder payload(String v) { this.payload = v; return this; }
        public Builder reason(String v) { this.reason = v; return this; }
        public Builder attempts(int v) { this.attempts = v; return this; }
        public Builder firstFailedAt(Instant v) { this.firstFailedAt = v; return this; }
        public Builder lastFailedAt(Instant v) { this.lastFailedAt = v; return this; }
        public Builder deadLetteredAt(Instant v) { this.deadLetteredAt = v; return this; }
        public Builder status(DeadLetterStatus v) { this.status = v; return this; }
        public Builder replayCount(int v) { this.replayCount = v; return this; }
        public Builder replayedBy(String v) { this.replayedBy = v; return this; }
        public Builder replayedAt(Instant v) { this.replayedAt = v; return this; }
        public DeadLetterEntry build() { return new DeadLetterEntry(this); }
    }

    public Long getId() { return id; }
    public String getSource() { return source; }
    public String getSourceId() { return sourceId; }
    public EventType getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getReason() { return reason; }
    public int getAttempts() { return attempts; }
    public Instant getFirstFailedAt() { return firstFailedAt; }
    public Instant getLastFailedAt() { return lastFailedAt; }
    public Instant getDeadLetteredAt() { return deadLetteredAt; }
    public DeadLetterStatus getStatus() { return status; }
    public int getReplayCount() { return replayCount; }
    public String getReplayedBy() { return replayedBy; }
    public Instant getReplayedAt() { return replayedAt; }

    public void setSource(String v) { this.source = v; }
    public void setSourceId(String v) { this.sourceId = v; }
    public void setEventType(EventType v) { this.eventType = v; }
    public void setPayload(String v) { this.payload = v; }
    public void setReason(String v) { this.reason = v; }
    public void setAttempts(int v) { this.attempts = v; }
    public void setFirstFailedAt(Instant v) { this.firstFailedAt = v; }
    public void setLastFailedAt(Instant v) { this.lastFailedAt = v; }
    public void setDeadLetteredAt(Instant v) { this.deadLetteredAt = v; }
    public void setStatus(DeadLetterStatus v) { this.status = v; }
    public void setReplayCount(int v) { this.replayCount = v; }
    public void setReplayedBy(String v) { this.replayedBy = v; }
    public void setReplayedAt(Instant v) { this.replayedAt = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeadLetterEntry d)) return false;
        return Objects.equals(id, d.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
