package com.techverito.banking.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "round_robin_state")
public class RoundRobinState {

    public static final Long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "last_assigned_rm_id")
    private Long lastAssignedRmId;

    public RoundRobinState() {}

    public RoundRobinState(Long id, Long lastAssignedRmId) {
        this.id = id;
        this.lastAssignedRmId = lastAssignedRmId;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getLastAssignedRmId() { return lastAssignedRmId; }
    public void setLastAssignedRmId(Long lastAssignedRmId) { this.lastAssignedRmId = lastAssignedRmId; }
}
