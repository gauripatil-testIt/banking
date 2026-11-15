package com.techverito.banking.entity;

import com.techverito.banking.event.EventType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

/**
 * One business event waiting to be published, written in the same transaction as the
 * business change it announces. The row holds the serialised envelope, so the drain
 * republishes exactly what the business transaction recorded, and the business key,
 * so per-key ordering is a query rather than a recomputation.
 *
 * <p>The unique {@code event_id} is what keeps a retry from storing a second copy of
 * the same event.</p>
 */
@Entity
@Table(name = "business_event_outbox")
public class BusinessEventOutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private EventType eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(nullable = false, length = 255)
    private String actor;

    @Column(name = "correlation_id", nullable = false, length = 255)
    private String correlationId;

    @Column(name = "business_key", nullable = false, length = 64)
    private String businessKey;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxEventStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "dead_lettered_at")
    private Instant deadLetteredAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public BusinessEventOutboxEntry() {}

    private BusinessEventOutboxEntry(Builder b) {
        this.id = b.id;
        this.eventId = b.eventId;
        this.eventType = b.eventType;
        this.eventVersion = b.eventVersion;
        this.occurredAt = b.occurredAt;
        this.customerId = b.customerId;
        this.accountId = b.accountId;
        this.actor = b.actor;
        this.correlationId = b.correlationId;
        this.businessKey = b.businessKey;
        this.payload = b.payload;
        this.status = b.status;
        this.attempts = b.attempts;
        this.nextAttemptAt = b.nextAttemptAt;
        this.lastError = b.lastError;
        this.publishedAt = b.publishedAt;
        this.deadLetteredAt = b.deadLetteredAt;
        this.createdAt = b.createdAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private String eventId;
        private EventType eventType;
        private int eventVersion;
        private Instant occurredAt;
        private Long customerId;
        private Long accountId;
        private String actor;
        private String correlationId;
        private String businessKey;
        private String payload;
        private OutboxEventStatus status;
        private int attempts;
        private Instant nextAttemptAt;
        private String lastError;
        private Instant publishedAt;
        private Instant deadLetteredAt;
        private Instant createdAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder eventId(String v) { this.eventId = v; return this; }
        public Builder eventType(EventType v) { this.eventType = v; return this; }
        public Builder eventVersion(int v) { this.eventVersion = v; return this; }
        public Builder occurredAt(Instant v) { this.occurredAt = v; return this; }
        public Builder customerId(Long v) { this.customerId = v; return this; }
        public Builder accountId(Long v) { this.accountId = v; return this; }
        public Builder actor(String v) { this.actor = v; return this; }
        public Builder correlationId(String v) { this.correlationId = v; return this; }
        public Builder businessKey(String v) { this.businessKey = v; return this; }
        public Builder payload(String v) { this.payload = v; return this; }
        public Builder status(OutboxEventStatus v) { this.status = v; return this; }
        public Builder attempts(int v) { this.attempts = v; return this; }
        public Builder nextAttemptAt(Instant v) { this.nextAttemptAt = v; return this; }
        public Builder lastError(String v) { this.lastError = v; return this; }
        public Builder publishedAt(Instant v) { this.publishedAt = v; return this; }
        public Builder deadLetteredAt(Instant v) { this.deadLetteredAt = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public BusinessEventOutboxEntry build() { return new BusinessEventOutboxEntry(this); }
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public EventType getEventType() { return eventType; }
    public int getEventVersion() { return eventVersion; }
    public Instant getOccurredAt() { return occurredAt; }
    public Long getCustomerId() { return customerId; }
    public Long getAccountId() { return accountId; }
    public String getActor() { return actor; }
    public String getCorrelationId() { return correlationId; }
    public String getBusinessKey() { return businessKey; }
    public String getPayload() { return payload; }
    public OutboxEventStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getDeadLetteredAt() { return deadLetteredAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setEventId(String v) { this.eventId = v; }
    public void setEventType(EventType v) { this.eventType = v; }
    public void setEventVersion(int v) { this.eventVersion = v; }
    public void setOccurredAt(Instant v) { this.occurredAt = v; }
    public void setCustomerId(Long v) { this.customerId = v; }
    public void setAccountId(Long v) { this.accountId = v; }
    public void setActor(String v) { this.actor = v; }
    public void setCorrelationId(String v) { this.correlationId = v; }
    public void setBusinessKey(String v) { this.businessKey = v; }
    public void setPayload(String v) { this.payload = v; }
    public void setStatus(OutboxEventStatus v) { this.status = v; }
    public void setAttempts(int v) { this.attempts = v; }
    public void setNextAttemptAt(Instant v) { this.nextAttemptAt = v; }
    public void setLastError(String v) { this.lastError = v; }
    public void setPublishedAt(Instant v) { this.publishedAt = v; }
    public void setDeadLetteredAt(Instant v) { this.deadLetteredAt = v; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BusinessEventOutboxEntry e)) return false;
        return Objects.equals(id, e.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
