package com.techverito.banking.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "round_robin_state")
public class RoundRobinState {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column
    private Long lastAssignedManagerId;

    public RoundRobinState() {}

    public RoundRobinState(Long id, Long lastAssignedManagerId) {
        this.id = id;
        this.lastAssignedManagerId = lastAssignedManagerId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getLastAssignedManagerId() { return lastAssignedManagerId; }
    public void setLastAssignedManagerId(Long lastAssignedManagerId) { this.lastAssignedManagerId = lastAssignedManagerId; }
}
