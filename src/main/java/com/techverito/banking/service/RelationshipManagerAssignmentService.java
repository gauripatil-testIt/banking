package com.techverito.banking.service;

import com.techverito.banking.entity.RelationshipManager;
import com.techverito.banking.entity.RoundRobinState;
import com.techverito.banking.repository.RelationshipManagerRepository;
import com.techverito.banking.repository.RoundRobinStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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
        RoundRobinState state = roundRobinStateRepository.findByIdForUpdate(RoundRobinState.SINGLETON_ID)
                .orElseGet(() -> new RoundRobinState(RoundRobinState.SINGLETON_ID, null));

        List<RelationshipManager> activeManagers = relationshipManagerRepository.findByActiveTrueOrderByIdAsc();
        if (activeManagers.isEmpty()) {
            return null;
        }

        RelationshipManager next = pickNext(activeManagers, state.getLastAssignedRmId());
        state.setLastAssignedRmId(next.getId());
        roundRobinStateRepository.save(state);
        return next.getId();
    }

    private RelationshipManager pickNext(List<RelationshipManager> activeManagers, Long lastAssignedRmId) {
        if (lastAssignedRmId == null) {
            return activeManagers.get(0);
        }
        int lastIndex = -1;
        for (int i = 0; i < activeManagers.size(); i++) {
            if (activeManagers.get(i).getId().equals(lastAssignedRmId)) {
                lastIndex = i;
                break;
            }
        }
        int nextIndex = (lastIndex + 1) % activeManagers.size();
        return activeManagers.get(nextIndex);
    }
}
