package com.techverito.banking.service;

import com.techverito.banking.dto.RelationshipManagerRequest;
import com.techverito.banking.dto.RelationshipManagerResponse;
import com.techverito.banking.entity.RelationshipManager;
import com.techverito.banking.entity.RotationCursor;
import com.techverito.banking.exception.BadRequestException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.RelationshipManagerRepository;
import com.techverito.banking.repository.RotationCursorRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class RelationshipManagerService {

    private final RelationshipManagerRepository relationshipManagerRepository;
    private final RotationCursorRepository rotationCursorRepository;

    public RelationshipManagerService(RelationshipManagerRepository relationshipManagerRepository,
                                       RotationCursorRepository rotationCursorRepository) {
        this.relationshipManagerRepository = relationshipManagerRepository;
        this.rotationCursorRepository = rotationCursorRepository;
    }

    public RelationshipManagerResponse create(RelationshipManagerRequest req) {
        relationshipManagerRepository.findByEmail(req.email()).ifPresent(existing -> {
            throw new BadRequestException("A relationship manager with email " + req.email() + " already exists");
        });
        RelationshipManager manager = RelationshipManager.builder()
                .name(req.name())
                .email(req.email())
                .active(true)
                .build();
        return RelationshipManagerResponse.from(relationshipManagerRepository.save(manager));
    }

    public RelationshipManagerResponse updateStatus(Long id, boolean active) {
        RelationshipManager manager = relationshipManagerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RelationshipManager", id));
        manager.setActive(active);
        return RelationshipManagerResponse.from(relationshipManagerRepository.save(manager));
    }

    @Transactional
    public RelationshipManager assignNextManager() {
        RotationCursor cursor = rotationCursorRepository.lockForUpdate(RotationCursor.SINGLETON_ID)
                .orElseGet(() -> rotationCursorRepository.save(new RotationCursor(RotationCursor.SINGLETON_ID, null)));

        List<RelationshipManager> managers = relationshipManagerRepository.findAllByOrderByIdAsc();
        if (managers.isEmpty()) {
            throw new BadRequestException("No active relationship managers available");
        }

        int startIndex = 0;
        Long lastId = cursor.getLastAssignedManagerId();
        if (lastId != null) {
            for (int i = 0; i < managers.size(); i++) {
                if (managers.get(i).getId().equals(lastId)) {
                    startIndex = i + 1;
                    break;
                }
            }
        }

        for (int offset = 0; offset < managers.size(); offset++) {
            int index = (startIndex + offset) % managers.size();
            RelationshipManager candidate = managers.get(index);
            if (candidate.isActive()) {
                cursor.setLastAssignedManagerId(candidate.getId());
                rotationCursorRepository.save(cursor);
                return candidate;
            }
        }

        throw new BadRequestException("No active relationship managers available");
    }
}
