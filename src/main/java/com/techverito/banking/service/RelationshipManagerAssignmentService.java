package com.techverito.banking.service;

import com.techverito.banking.config.RelationshipManagerPoolProperties;
import com.techverito.banking.exception.NoAvailableRelationshipManagerException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RelationshipManagerAssignmentService {

    private final RelationshipManagerPoolProperties poolProperties;
    private int cursor = 0;

    public RelationshipManagerAssignmentService(RelationshipManagerPoolProperties poolProperties) {
        this.poolProperties = poolProperties;
    }

    public synchronized String nextManager() {
        List<String> names = poolProperties.getNames();
        if (names == null || names.isEmpty()) {
            throw new NoAvailableRelationshipManagerException("No available relationship managers");
        }

        if (cursor >= names.size()) {
            cursor = 0;
        }

        return names.get(cursor++);
    }
}
