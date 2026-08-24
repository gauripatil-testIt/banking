package com.techverito.banking.repository;

import com.techverito.banking.entity.RoundRobinState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface RoundRobinStateRepository extends JpaRepository<RoundRobinState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RoundRobinState> findById(Long id);
}
