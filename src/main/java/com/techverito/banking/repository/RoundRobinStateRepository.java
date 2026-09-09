package com.techverito.banking.repository;

import com.techverito.banking.entity.RoundRobinState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RoundRobinStateRepository extends JpaRepository<RoundRobinState, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from RoundRobinState s where s.id = :id")
    Optional<RoundRobinState> findByIdForUpdate(@Param("id") Long id);
}
