package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "data_exports")
public class DataExport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "exported_by", nullable = false, length = 255)
    private String exportedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "export_type", nullable = false, length = 20)
    private ExportType exportType;

    @Column(name = "record_count", nullable = false)
    private long recordCount;

    @Column(name = "exported_at", nullable = false)
    private Instant exportedAt;

    public DataExport() {}

    private DataExport(Builder b) {
        this.id = b.id;
        this.exportedBy = b.exportedBy;
        this.exportType = b.exportType;
        this.recordCount = b.recordCount;
        this.exportedAt = b.exportedAt;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private String exportedBy;
        private ExportType exportType;
        private long recordCount;
        private Instant exportedAt;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder exportedBy(String v) { this.exportedBy = v; return this; }
        public Builder exportType(ExportType v) { this.exportType = v; return this; }
        public Builder recordCount(long v) { this.recordCount = v; return this; }
        public Builder exportedAt(Instant v) { this.exportedAt = v; return this; }
        public DataExport build() { return new DataExport(this); }
    }

    public Long getId() { return id; }
    public String getExportedBy() { return exportedBy; }
    public ExportType getExportType() { return exportType; }
    public long getRecordCount() { return recordCount; }
    public Instant getExportedAt() { return exportedAt; }

    public void setExportedBy(String v) { this.exportedBy = v; }
    public void setExportType(ExportType v) { this.exportType = v; }
    public void setRecordCount(long v) { this.recordCount = v; }
    public void setExportedAt(Instant v) { this.exportedAt = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DataExport e)) return false;
        return Objects.equals(id, e.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
