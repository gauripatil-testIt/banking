package com.techverito.banking.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "relationship_managers")
public class RelationshipManager {

    @Id
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private RelationshipManagerStatus status;

    private int maxCapacity;

    private int assignedCount;

    public RelationshipManager() {}

    public RelationshipManager(Long id, String name, RelationshipManagerStatus status, int maxCapacity, int assignedCount) {
        this.id = id;
        this.name = name;
        this.status = status;
        this.maxCapacity = maxCapacity;
        this.assignedCount = assignedCount;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Long id;
        private String name;
        private RelationshipManagerStatus status;
        private int maxCapacity;
        private int assignedCount;

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder status(RelationshipManagerStatus status) {
            this.status = status;
            return this;
        }

        public Builder maxCapacity(long maxCapacity) {
            this.maxCapacity = (int) maxCapacity;
            return this;
        }

        public Builder assignedCount(long assignedCount) {
            this.assignedCount = (int) assignedCount;
            return this;
        }

        public RelationshipManager build() {
            return new RelationshipManager(id, name, status, maxCapacity, assignedCount);
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public RelationshipManagerStatus getStatus() {
        return status;
    }

    public void setStatus(RelationshipManagerStatus status) {
        this.status = status;
    }

    public int getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(int maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public int getAssignedCount() {
        return assignedCount;
    }

    public void setAssignedCount(int assignedCount) {
        this.assignedCount = assignedCount;
    }
}
