package com.techverito.banking.entity;

import com.techverito.banking.event.EventType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

/**
 * The marker that says one event has already been handled by this application, so a second
 * delivery of the same {@code eventId} – a replay of a dead-lettered item, or a duplicate the
 * broker delivered – does not repeat the effect.
 *
 * <p>The marker is written in the same transaction as the handler's effect, so the two commit
 * or roll back together: a handler that fails leaves no marker behind and its event is handled
 * again on the next delivery. The unique {@code event_id} is what stops a concurrent duplicate
 * from committing a second effect.</p>
 */
@Entity
@Table(name = "processed_events")
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private EventType eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    public ProcessedEvent() {}

    private ProcessedEvent(Builder b) {
        this.id = b.id;
        this.eventId = b.eventId;
        this.eventType = b.eventType;
        this.processedAt = b.processedAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private String eventId;
        private EventType eventType;
        private Instant processedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder eventId(String v) { this.eventId = v; return this; }
        public Builder eventType(EventType v) { this.eventType = v; return this; }
        public Builder processedAt(Instant v) { this.processedAt = v; return this; }
        public ProcessedEvent build() { return new ProcessedEvent(this); }
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public EventType getEventType() { return eventType; }
    public Instant getProcessedAt() { return processedAt; }

    public void setEventId(String v) { this.eventId = v; }
    public void setEventType(EventType v) { this.eventType = v; }
    public void setProcessedAt(Instant v) { this.processedAt = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProcessedEvent p)) return false;
        return Objects.equals(id, p.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
