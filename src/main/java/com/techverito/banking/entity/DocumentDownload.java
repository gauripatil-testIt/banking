package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "document_downloads")
public class DocumentDownload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id", nullable = false)
    private CustomerDocument document;

    @Column(name = "downloaded_by", nullable = false)
    private String downloadedBy;

    @Column(name = "downloaded_at", nullable = false)
    private Instant downloadedAt;

    public DocumentDownload() {}

    private DocumentDownload(Builder b) {
        this.id = b.id;
        this.document = b.document;
        this.downloadedBy = b.downloadedBy;
        this.downloadedAt = b.downloadedAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private CustomerDocument document;
        private String downloadedBy;
        private Instant downloadedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder document(CustomerDocument v) { this.document = v; return this; }
        public Builder downloadedBy(String v) { this.downloadedBy = v; return this; }
        public Builder downloadedAt(Instant v) { this.downloadedAt = v; return this; }
        public DocumentDownload build() { return new DocumentDownload(this); }
    }

    public Long getId() { return id; }
    public CustomerDocument getDocument() { return document; }
    public String getDownloadedBy() { return downloadedBy; }
    public Instant getDownloadedAt() { return downloadedAt; }

    public void setDocument(CustomerDocument v) { this.document = v; }
    public void setDownloadedBy(String v) { this.downloadedBy = v; }
    public void setDownloadedAt(Instant v) { this.downloadedAt = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DocumentDownload d)) return false;
        return Objects.equals(id, d.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
