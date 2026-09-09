package com.techverito.banking.repository;

import com.techverito.banking.entity.RelationshipManager;
import com.techverito.banking.entity.RelationshipManagerStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RelationshipManagerRepository extends JpaRepository<RelationshipManager, Long> {

    @Query("select rm from RelationshipManager rm where rm.status = :status order by rm.id")
    List<RelationshipManager> findByStatusOrderedById(@Param("status") RelationshipManagerStatus status);

    // Kept for backward compatibility with existing tests.
    @Query("select rm from RelationshipManager rm where rm.status = :status order by rm.id asc")
    List<RelationshipManager> findAllByStatusOrderByIdAsc(@Param("status") RelationshipManagerStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rm from RelationshipManager rm where rm.id = :id")
    Optional<RelationshipManager> findByIdForUpdate(@Param("id") Long id);

    // Kept for backward compatibility with existing tests.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select rm from RelationshipManager rm where rm.id = :id")
    Optional<RelationshipManager> findByIdForCapacityUpdate(@Param("id") Long id);
}
