package com.techverito.banking.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "round_robin_state")
public class RoundRobinState {

    @Id
    private Long id;

    private Long lastAssignedManagerId;

    public RoundRobinState() {}

    public RoundRobinState(Long id, Long lastAssignedManagerId) {
        this.id = id;
        this.lastAssignedManagerId = lastAssignedManagerId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getLastAssignedManagerId() {
        return lastAssignedManagerId;
    }

    public void setLastAssignedManagerId(Long lastAssignedManagerId) {
        this.lastAssignedManagerId = lastAssignedManagerId;
    }
}
