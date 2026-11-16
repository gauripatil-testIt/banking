package com.techverito.banking.entity;

import com.techverito.banking.event.task.TaskType;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

/**
 * One background task waiting to be submitted to RabbitMQ, written in the same
 * transaction as the business action that asked for it. The row holds the serialised
 * {@link com.techverito.banking.event.task.BackgroundTaskMessage}, so the drain submits
 * exactly what the business transaction recorded, and it is the copy that survives a
 * broker restart or an outage.
 *
 * <p>The unique {@code task_id} is what keeps a duplicate submission – the same task for
 * the same record – from being queued twice.</p>
 */
@Entity
@Table(name = "background_task_outbox")
public class BackgroundTaskOutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, unique = true, length = 36)
    private String taskId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 40)
    private TaskType taskType;

    @Column(name = "record_type", nullable = false, length = 40)
    private String recordType;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "requested_by", nullable = false, length = 255)
    private String requestedBy;

    @Column(name = "correlation_id", nullable = false, length = 255)
    private String correlationId;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BackgroundTaskStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public BackgroundTaskOutboxEntry() {}

    private BackgroundTaskOutboxEntry(Builder b) {
        this.id = b.id;
        this.taskId = b.taskId;
        this.taskType = b.taskType;
        this.recordType = b.recordType;
        this.recordId = b.recordId;
        this.customerId = b.customerId;
        this.requestedBy = b.requestedBy;
        this.correlationId = b.correlationId;
        this.payload = b.payload;
        this.status = b.status;
        this.attempts = b.attempts;
        this.nextAttemptAt = b.nextAttemptAt;
        this.lastError = b.lastError;
        this.publishedAt = b.publishedAt;
        this.createdAt = b.createdAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private String taskId;
        private TaskType taskType;
        private String recordType;
        private Long recordId;
        private Long customerId;
        private String requestedBy;
        private String correlationId;
        private String payload;
        private BackgroundTaskStatus status;
        private int attempts;
        private Instant nextAttemptAt;
        private String lastError;
        private Instant publishedAt;
        private Instant createdAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder taskId(String v) { this.taskId = v; return this; }
        public Builder taskType(TaskType v) { this.taskType = v; return this; }
        public Builder recordType(String v) { this.recordType = v; return this; }
        public Builder recordId(Long v) { this.recordId = v; return this; }
        public Builder customerId(Long v) { this.customerId = v; return this; }
        public Builder requestedBy(String v) { this.requestedBy = v; return this; }
        public Builder correlationId(String v) { this.correlationId = v; return this; }
        public Builder payload(String v) { this.payload = v; return this; }
        public Builder status(BackgroundTaskStatus v) { this.status = v; return this; }
        public Builder attempts(int v) { this.attempts = v; return this; }
        public Builder nextAttemptAt(Instant v) { this.nextAttemptAt = v; return this; }
        public Builder lastError(String v) { this.lastError = v; return this; }
        public Builder publishedAt(Instant v) { this.publishedAt = v; return this; }
        public Builder createdAt(Instant v) { this.createdAt = v; return this; }
        public BackgroundTaskOutboxEntry build() { return new BackgroundTaskOutboxEntry(this); }
    }

    public Long getId() { return id; }
    public String getTaskId() { return taskId; }
    public TaskType getTaskType() { return taskType; }
    public String getRecordType() { return recordType; }
    public Long getRecordId() { return recordId; }
    public Long getCustomerId() { return customerId; }
    public String getRequestedBy() { return requestedBy; }
    public String getCorrelationId() { return correlationId; }
    public String getPayload() { return payload; }
    public BackgroundTaskStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void setTaskId(String v) { this.taskId = v; }
    public void setTaskType(TaskType v) { this.taskType = v; }
    public void setRecordType(String v) { this.recordType = v; }
    public void setRecordId(Long v) { this.recordId = v; }
    public void setCustomerId(Long v) { this.customerId = v; }
    public void setRequestedBy(String v) { this.requestedBy = v; }
    public void setCorrelationId(String v) { this.correlationId = v; }
    public void setPayload(String v) { this.payload = v; }
    public void setStatus(BackgroundTaskStatus v) { this.status = v; }
    public void setAttempts(int v) { this.attempts = v; }
    public void setNextAttemptAt(Instant v) { this.nextAttemptAt = v; }
    public void setLastError(String v) { this.lastError = v; }
    public void setPublishedAt(Instant v) { this.publishedAt = v; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BackgroundTaskOutboxEntry e)) return false;
        return Objects.equals(id, e.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
