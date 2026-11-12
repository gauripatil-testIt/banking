package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "customer_documents")
public class CustomerDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentType documentType;

    @Column(nullable = false)
    private String originalFileName;

    @Column(name = "stored_file_name", nullable = false, unique = true)
    private String storedFileName;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(length = 1000)
    private String note;

    @Column(name = "uploaded_by", nullable = false)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    public CustomerDocument() {}

    private CustomerDocument(Builder b) {
        this.id = b.id;
        this.customer = b.customer;
        this.documentType = b.documentType;
        this.originalFileName = b.originalFileName;
        this.storedFileName = b.storedFileName;
        this.sizeBytes = b.sizeBytes;
        this.note = b.note;
        this.uploadedBy = b.uploadedBy;
        this.uploadedAt = b.uploadedAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private Customer customer;
        private DocumentType documentType;
        private String originalFileName;
        private String storedFileName;
        private long sizeBytes;
        private String note;
        private String uploadedBy;
        private Instant uploadedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder customer(Customer v) { this.customer = v; return this; }
        public Builder documentType(DocumentType v) { this.documentType = v; return this; }
        public Builder originalFileName(String v) { this.originalFileName = v; return this; }
        public Builder storedFileName(String v) { this.storedFileName = v; return this; }
        public Builder sizeBytes(long v) { this.sizeBytes = v; return this; }
        public Builder note(String v) { this.note = v; return this; }
        public Builder uploadedBy(String v) { this.uploadedBy = v; return this; }
        public Builder uploadedAt(Instant v) { this.uploadedAt = v; return this; }
        public CustomerDocument build() { return new CustomerDocument(this); }
    }

    public Long getId() { return id; }
    public Customer getCustomer() { return customer; }
    public DocumentType getDocumentType() { return documentType; }
    public String getOriginalFileName() { return originalFileName; }
    public String getStoredFileName() { return storedFileName; }
    public long getSizeBytes() { return sizeBytes; }
    public String getNote() { return note; }
    public String getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }

    public void setCustomer(Customer v) { this.customer = v; }
    public void setDocumentType(DocumentType v) { this.documentType = v; }
    public void setOriginalFileName(String v) { this.originalFileName = v; }
    public void setStoredFileName(String v) { this.storedFileName = v; }
    public void setSizeBytes(long v) { this.sizeBytes = v; }
    public void setNote(String v) { this.note = v; }
    public void setUploadedBy(String v) { this.uploadedBy = v; }
    public void setUploadedAt(Instant v) { this.uploadedAt = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CustomerDocument d)) return false;
        return Objects.equals(id, d.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
