package com.techverito.banking.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "rotation_cursor")
public class RotationCursor {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id;

    private Long lastAssignedManagerId;

    public RotationCursor() {}

    public RotationCursor(Long id, Long lastAssignedManagerId) {
        this.id = id;
        this.lastAssignedManagerId = lastAssignedManagerId;
    }

    public Long getId() { return id; }
    public Long getLastAssignedManagerId() { return lastAssignedManagerId; }

    public void setId(Long id) { this.id = id; }
    public void setLastAssignedManagerId(Long lastAssignedManagerId) { this.lastAssignedManagerId = lastAssignedManagerId; }
}
