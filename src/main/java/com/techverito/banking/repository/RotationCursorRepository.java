package com.techverito.banking.repository;

import com.techverito.banking.entity.RotationCursor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RotationCursorRepository extends JpaRepository<RotationCursor, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RotationCursor r where r.id = :id")
    Optional<RotationCursor> lockForUpdate(@Param("id") Long id);
}
