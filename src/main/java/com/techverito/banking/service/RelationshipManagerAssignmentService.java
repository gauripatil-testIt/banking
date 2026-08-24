package com.techverito.banking.service;

import com.techverito.banking.entity.RelationshipManager;
import com.techverito.banking.entity.RoundRobinState;
import com.techverito.banking.exception.NoActiveRelationshipManagerException;
import com.techverito.banking.repository.RelationshipManagerRepository;
import com.techverito.banking.repository.RoundRobinStateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class RelationshipManagerAssignmentService {

    private final RelationshipManagerRepository relationshipManagerRepository;
    private final RoundRobinStateRepository roundRobinStateRepository;

    public RelationshipManagerAssignmentService(RelationshipManagerRepository relationshipManagerRepository,
                                                 RoundRobinStateRepository roundRobinStateRepository) {
        this.relationshipManagerRepository = relationshipManagerRepository;
        this.roundRobinStateRepository = roundRobinStateRepository;
    }

    public RelationshipManager assignNext() {
        List<RelationshipManager> activeManagers = relationshipManagerRepository.findByActiveTrueOrderByIdAsc();
        if (activeManagers.isEmpty()) {
            throw new NoActiveRelationshipManagerException();
        }

        RoundRobinState state = roundRobinStateRepository.findById(RoundRobinState.SINGLETON_ID)
                .orElseGet(() -> new RoundRobinState(RoundRobinState.SINGLETON_ID, null));

        Long lastAssignedId = state.getLastAssignedManagerId();
        int nextIndex = 0;
        if (lastAssignedId != null) {
            int lastIndex = -1;
            for (int i = 0; i < activeManagers.size(); i++) {
                if (activeManagers.get(i).getId().equals(lastAssignedId)) {
                    lastIndex = i;
                    break;
                }
            }
            nextIndex = (lastIndex + 1) % activeManagers.size();
        }

        RelationshipManager chosen = activeManagers.get(nextIndex);
        state.setLastAssignedManagerId(chosen.getId());
        roundRobinStateRepository.save(state);
        return chosen;
    }
}
