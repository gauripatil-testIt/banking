package com.techverito.banking.dto;

import com.techverito.banking.entity.RelationshipManager;

public record RelationshipManagerSummary(
        Long id,
        String name,
        String email
) {
    public static RelationshipManagerSummary from(RelationshipManager m) {
        if (m == null) {
            return null;
        }
        return new RelationshipManagerSummary(m.getId(), m.getName(), m.getEmail());
    }
}
