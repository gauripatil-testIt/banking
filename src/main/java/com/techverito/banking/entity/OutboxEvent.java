package com.techverito.banking.entity;

import com.techverito.banking.event.SubjectType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One business event waiting to be published to Kafka, stored in the durable outbox.
 *
 * <p>The row is inserted in the same transaction as the business change it announces, so it
 * commits or rolls back with that change and a broker outage can never lose an event. The row
 * carries the parts the relay needs without re-reading the business data: the exact JSON payload
 * (the BANK-6 wire format), the topic, and the ordering key {@code <subjectType>-<subjectId>} that
 * is also the Kafka partition key. {@code sequenceNo} orders the events of one ordering key, so the
 * relay can never send a later change of an account before an earlier one.</p>
 *
 * <p>{@code status} is {@link OutboxEventStatus#PENDING} until the broker acknowledges the event, at
 * which point the row is deleted. A transient failure only moves {@code nextAttemptAt} forward and
 * raises {@code attemptCount}; a permanent failure keeps the row as
 * {@link OutboxEventStatus#FAILED} with the reason for investigation.</p>
 */
@Entity
@Table(name = "outboxevent")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The id carried on the event envelope, and what a consumer deduplicates on. */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private UUID eventId;

    /** The lower-case dotted wire value of the event type, for example {@code customer.registered}. */
    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private Integer eventVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private Long subjectId;

    @Column(name = "sequence_no", nullable = false)
    private Long sequenceNo;

    /** {@code <subjectType>-<subjectId>}, for example {@code ACCOUNT-42}: the Kafka message key. */
    @Column(name = "ordering_key", nullable = false, length = 64)
    private String orderingKey;

    @Column(nullable = false, length = 255)
    private String topic;

    /** The exact JSON written by {@code BusinessEventJson.toJson}, sent to the broker verbatim. */
    @Lob
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private OutboxEventStatus status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** The longest reason varchar2(1000) can hold, so a long message cannot fail the update. */
    private static final int FAILURE_REASON_MAX_LENGTH = 1000;

    public OutboxEvent() {}

    private OutboxEvent(Builder b) {
        this.id = b.id;
        this.eventId = b.eventId;
        this.eventType = b.eventType;
        this.eventVersion = b.eventVersion;
        this.subjectType = b.subjectType;
        this.subjectId = b.subjectId;
        this.sequenceNo = b.sequenceNo;
        this.orderingKey = b.orderingKey;
        this.topic = b.topic;
        this.payload = b.payload;
        this.status = b.status;
        this.attemptCount = b.attemptCount;
        this.failureReason = b.failureReason;
        this.createdAt = b.createdAt;
        this.nextAttemptAt = b.nextAttemptAt;
        this.publishedAt = b.publishedAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private UUID eventId;
        private String eventType;
        private Integer eventVersion;
        private SubjectType subjectType;
        private Long subjectId;
        private Long sequenceNo;
        private String orderingKey;
        private String topic;
        private String payload;
        private OutboxEventStatus status = OutboxEventStatus.PENDING;
        private int attemptCount;
        private String failureReason;
        private Instant createdAt;
        private Instant nextAttemptAt;
        private Instant publishedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder eventId(UUID v) { this.eventId = v; return this; }
        public Builder eventType(String v) { this.eventType = v; return this; }
        public Builder eventVersion(Integer v) { this.eventVersion = v; return this; }
        public Builder subjectType(SubjectType v) { this.subjectType = v; return this; }
        public Builder subjectId(Long v) { this.subjectId = v; return this; }
        public Builder sequenceNo(Long v) { this.sequenceNo = v; return this; }
        public Builder orderingKey(String v) { this.orderingKey = v; return this; }
        public Builder topic(String v) { this.topic = v; return this; }
        public Builder payload(String v) { this.payload = v; return this; }
        public Builder status(OutboxEventStatus v) { this.status = v; return this; }
        public Builder attemptCount(int v) { this.attemptCount = v; return this; }
        public Builder failureReason(String v) { this.failureReason = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public Builder nextAttemptAt(Instant v) { this.nextAttemptAt = v; return this; }
        public Builder publishedAt(Instant v) { this.publishedAt = v; return this; }
        public OutboxEvent build() { return new OutboxEvent(this); }
    }

    public Long getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Integer getEventVersion() { return eventVersion; }
    public SubjectType getSubjectType() { return subjectType; }
    public Long getSubjectId() { return subjectId; }
    public Long getSequenceNo() { return sequenceNo; }
    public String getOrderingKey() { return orderingKey; }
    public String getTopic() { return topic; }
    public String getPayload() { return payload; }
    public OutboxEventStatus getStatus() { return status; }
    public int getAttemptCount() { return attemptCount; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getPublishedAt() { return publishedAt; }

    public void setEventId(UUID v) { this.eventId = v; }
    public void setEventType(String v) { this.eventType = v; }
    public void setEventVersion(Integer v) { this.eventVersion = v; }
    public void setSubjectType(SubjectType v) { this.subjectType = v; }
    public void setSubjectId(Long v) { this.subjectId = v; }
    public void setSequenceNo(Long v) { this.sequenceNo = v; }
    public void setOrderingKey(String v) { this.orderingKey = v; }
    public void setTopic(String v) { this.topic = v; }
    public void setPayload(String v) { this.payload = v; }
    public void setStatus(OutboxEventStatus v) { this.status = v; }
    public void setAttemptCount(int v) { this.attemptCount = v; }
    public void setFailureReason(String v) { this.failureReason = v; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public void setNextAttemptAt(Instant v) { this.nextAttemptAt = v; }
    public void setPublishedAt(Instant v) { this.publishedAt = v; }

    /**
     * Records a failed send attempt: counts it, keeps the reason and moves the row to its next
     * attempt. The status stays {@link OutboxEventStatus#PENDING} because the relay retries a
     * transient failure indefinitely instead of giving up on the event.
     */
    public void recordFailedAttempt(String reason, Instant nextAttemptAt) {
        this.attemptCount = this.attemptCount + 1;
        this.failureReason = truncate(reason);
        this.nextAttemptAt = nextAttemptAt;
    }

    /**
     * Marks an event the broker can never accept (for example an invalid topic) as
     * {@link OutboxEventStatus#FAILED}. The row itself is the investigation record - event id plus
     * reason - so it stays in the table and is no longer selected for sending.
     */
    public void markUnpublishable(String reason) {
        this.status = OutboxEventStatus.FAILED;
        this.failureReason = truncate(reason);
    }

    private String truncate(String reason) {
        if (reason == null || reason.length() <= FAILURE_REASON_MAX_LENGTH) {
            return reason;
        }
        return reason.substring(0, FAILURE_REASON_MAX_LENGTH);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OutboxEvent e)) return false;
        return Objects.equals(id, e.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
