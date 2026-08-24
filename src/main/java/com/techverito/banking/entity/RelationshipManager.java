package com.techverito.banking.entity;

import jakarta.persistence.*;

import java.util.Objects;

@Entity
@Table(name = "relationship_managers")
public class RelationshipManager {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active;

    public RelationshipManager() {}

    private RelationshipManager(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.active = b.active;
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private Long id;
        private String name;
        private boolean active;

        public Builder id(Long v) { this.id = v; return this; }
        public Builder name(String v) { this.name = v; return this; }
        public Builder active(boolean v) { this.active = v; return this; }
        public RelationshipManager build() { return new RelationshipManager(this); }
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public boolean isActive() { return active; }

    public void setName(String v) { this.name = v; }
    public void setActive(boolean v) { this.active = v; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RelationshipManager r)) return false;
        return Objects.equals(id, r.id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
