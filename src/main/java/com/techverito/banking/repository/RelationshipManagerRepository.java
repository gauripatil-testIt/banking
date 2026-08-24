package com.techverito.banking.repository;

import com.techverito.banking.entity.RelationshipManager;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RelationshipManagerRepository extends JpaRepository<RelationshipManager, Long> {
    List<RelationshipManager> findByActiveTrueOrderByIdAsc();
}
