package com.techverito.banking.dto;

import com.techverito.banking.entity.RelationshipManager;

public record RelationshipManagerResponse(
        Long id,
        String name,
        String email,
        boolean active
) {
    public static RelationshipManagerResponse from(RelationshipManager m) {
        return new RelationshipManagerResponse(
                m.getId(), m.getName(), m.getEmail(), m.isActive()
        );
    }
}
