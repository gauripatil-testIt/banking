package com.techverito.banking.repository;

import com.techverito.banking.entity.RelationshipManager;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RelationshipManagerRepository extends JpaRepository<RelationshipManager, Long> {
    Optional<RelationshipManager> findByEmail(String email);
    List<RelationshipManager> findAllByOrderByIdAsc();
}
