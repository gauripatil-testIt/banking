package com.techverito.banking.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class RelationshipManagerAssignmentService {

    private static final List<String> DEFAULT_MANAGERS = List.of("manager1", "manager2", "manager3");

    private static final AtomicInteger counter = new AtomicInteger(0);

    private static volatile List<String> managers = DEFAULT_MANAGERS;

    public RelationshipManagerAssignmentService(
            @Value("${banking.relationship-managers:}") String relationshipManagers) {
        managers = parseManagers(relationshipManagers);
    }

    private static List<String> parseManagers(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_MANAGERS;
        }
        List<String> parsed = new ArrayList<>();
        for (String name : raw.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                parsed.add(trimmed);
            }
        }
        return parsed.isEmpty() ? DEFAULT_MANAGERS : Collections.unmodifiableList(parsed);
    }

    public static String assignNext() {
        List<String> pool = managers;
        if (pool == null || pool.isEmpty()) {
            throw new IllegalStateException("No relationship managers configured for assignment");
        }
        int index = Math.floorMod(counter.getAndIncrement(), pool.size());
        return pool.get(index);
    }
}
