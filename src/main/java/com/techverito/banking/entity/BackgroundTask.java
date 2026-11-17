package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

/**
 * One unit of slow work on its way to a background worker: the outbox row BANK-8 writes
 * in the same transaction as the business change, and that the scheduled relay publishes
 * to RabbitMQ.
 *
 * <p>The row is the source of truth. It carries the already serialised business event
 * ({@code messageJson}), so the message that is finally published is exactly the one that
 * was built when the change happened, even if the broker was unreachable for a while. The
 * unique constraint on {@code (task_type, business_key)} is what makes a repeated
 * submission of the same task insert nothing: {@code businessKey} is the business
 * identity of the work, for example the document id for a scan.</p>
 *
 * <p>{@code attempts} and {@code nextAttemptAt} are the retry state the relay uses; a row
 * stays PENDING until the broker confirmed its message and only then becomes
 * {@link BackgroundTaskStatus#SUBMITTED} with {@code publishedAt} set.</p>
 */
@Entity
@Table(name = "background_tasks",
        uniqueConstraints = @UniqueConstraint(name = "uq_background_tasks_task_key",
                columnNames = {"task_type", "business_key"}))
public class BackgroundTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 40)
    private BackgroundTaskType taskType;

    @Column(name = "business_key", nullable = false, length = 255)
    private String businessKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BackgroundTaskStatus status;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "correlation_id", nullable = false, length = 255)
    private String correlationId;

    @Column(name = "requested_by", nullable = false, length = 255)
    private String requestedBy;

    @Lob
    @Column(name = "message_json", nullable = false)
    private String messageJson;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public BackgroundTask() {}

    private BackgroundTask(Builder b) {
        this.id = b.id;
        this.taskType = b.taskType;
        this.businessKey = b.businessKey;
        this.status = b.status;
        this.eventId = b.eventId;
        this.correlationId = b.correlationId;
        this.requestedBy = b.requestedBy;
        this.messageJson = b.messageJson;
        this.attempts = b.attempts;
        this.nextAttemptAt = b.nextAttemptAt;
        this.publishedAt = b.publishedAt;
        this.createdAt = b.createdAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private BackgroundTaskType taskType;
        private String businessKey;
        private BackgroundTaskStatus status = BackgroundTaskStatus.PENDING;
        private String eventId;
        private String correlationId;
        private String requestedBy;
        private String messageJson;
        private int attempts;
        private Instant nextAttemptAt;
        private Instant publishedAt;
        private Instant createdAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder taskType(BackgroundTaskType v) { this.taskType = v; return this; }
        public Builder businessKey(String v) { this.businessKey = v; return this; }
        public Builder status(BackgroundTaskStatus v) { this.status = v; return this; }
        public Builder eventId(String v) { this.eventId = v; return this; }
        public Builder correlationId(String v) { this.correlationId = v; return this; }
        public Builder requestedBy(String v) { this.requestedBy = v; return this; }
        public Builder messageJson(String v) { this.messageJson = v; return this; }
        public Builder attempts(int v) { this.attempts = v; return this; }
        public Builder nextAttemptAt(Instant v) { this.nextAttemptAt = v; return this; }
        public Builder publishedAt(Instant v) { this.publishedAt = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public BackgroundTask build() { return new BackgroundTask(this); }
    }

    public Long getId() { return id; }
    public BackgroundTaskType getTaskType() { return taskType; }
    public String getBusinessKey() { return businessKey; }
    public BackgroundTaskStatus getStatus() { return status; }
    public String getEventId() { return eventId; }
    public String getCorrelationId() { return correlationId; }
    public String getRequestedBy() { return requestedBy; }
    public String getMessageJson() { return messageJson; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setStatus(BackgroundTaskStatus v) { this.status = v; }
    public void setAttempts(int v) { this.attempts = v; }
    public void setNextAttemptAt(Instant v) { this.nextAttemptAt = v; }
    public void setPublishedAt(Instant v) { this.publishedAt = v; }
    public void setMessageJson(String v) { this.messageJson = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BackgroundTask t)) return false;
        return Objects.equals(id, t.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
