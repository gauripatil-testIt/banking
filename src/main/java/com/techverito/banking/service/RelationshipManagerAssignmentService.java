package com.techverito.banking.service;

import com.techverito.banking.entity.RelationshipManager;
import com.techverito.banking.entity.RelationshipManagerStatus;
import com.techverito.banking.entity.RoundRobinState;
import com.techverito.banking.exception.NoAvailableRelationshipManagerException;
import com.techverito.banking.repository.RelationshipManagerRepository;
import com.techverito.banking.repository.RoundRobinStateRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class RelationshipManagerAssignmentService {

    private final RelationshipManagerRepository relationshipManagerRepository;
    private final RoundRobinStateRepository roundRobinStateRepository;

    public RelationshipManagerAssignmentService(RelationshipManagerRepository relationshipManagerRepository,
                                                RoundRobinStateRepository roundRobinStateRepository) {
        this.relationshipManagerRepository = relationshipManagerRepository;
        this.roundRobinStateRepository = roundRobinStateRepository;
    }

    @Transactional
    public Long assignNext() {
        RoundRobinState roundRobinState = roundRobinStateRepository.findByIdForUpdate(1L)
                .orElseGet(() -> {
                    RoundRobinState state = new RoundRobinState();
                    state.setId(1L);
                    state.setLastAssignedManagerId(null);
                    return state;
                });

        List<RelationshipManager> activeManagers = relationshipManagerRepository
                .findByStatusOrderedById(RelationshipManagerStatus.ACTIVE);

        if (activeManagers == null || activeManagers.isEmpty()) {
            throw new NoAvailableRelationshipManagerException("No active relationship managers available");
        }

        activeManagers.sort(Comparator.comparing(RelationshipManager::getId));

        Long lastAssignedManagerId = roundRobinState.getLastAssignedManagerId();

        int startIndex = 0;
        if (lastAssignedManagerId != null) {
            int idx = -1;
            for (int i = 0; i < activeManagers.size(); i++) {
                if (activeManagers.get(i).getId().equals(lastAssignedManagerId)) {
                    idx = i;
                    break;
                }
            }
            startIndex = idx >= 0 ? (idx + 1) % activeManagers.size() : 0;
        }

        RelationshipManager chosen = null;
        for (int offset = 0; offset < activeManagers.size(); offset++) {
            RelationshipManager candidate = activeManagers.get((startIndex + offset) % activeManagers.size());
            if (candidate.getAssignedCount() < candidate.getMaxCapacity()) {
                chosen = candidate;
                break;
            }
        }

        if (chosen == null) {
            throw new NoAvailableRelationshipManagerException("All active relationship managers are at capacity");
        }

        // Lock the chosen manager row to update assignedCount atomically.
        Optional<RelationshipManager> lockedChosenOpt = relationshipManagerRepository.findByIdForUpdate(chosen.getId());
        RelationshipManager lockedChosen = lockedChosenOpt
                .orElseThrow(() -> new NoAvailableRelationshipManagerException("Selected relationship manager not found"));

        if (lockedChosen.getAssignedCount() >= lockedChosen.getMaxCapacity()) {
            // Capacity changed since the initial scan; treat as no availability for strictness.
            throw new NoAvailableRelationshipManagerException("All active relationship managers are at capacity");
        }

        lockedChosen.setAssignedCount(lockedChosen.getAssignedCount() + 1);
        RelationshipManager savedManager = relationshipManagerRepository.save(lockedChosen);

        roundRobinState.setLastAssignedManagerId(savedManager.getId());
        roundRobinStateRepository.save(roundRobinState);

        return savedManager.getId();
    }
}
