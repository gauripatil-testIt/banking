package com.techverito.banking.repository;

import com.techverito.banking.entity.BackgroundTaskOutboxEntry;
import com.techverito.banking.entity.BackgroundTaskStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface BackgroundTaskOutboxRepository extends JpaRepository<BackgroundTaskOutboxEntry, Long> {

    /**
     * The rows the drain may publish now, oldest first, so a task recorded earlier is
     * submitted before one recorded later.
     */
    List<BackgroundTaskOutboxEntry> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            BackgroundTaskStatus status, Instant now, Pageable page);

    /** True when this task – the same type for the same record – was already submitted. */
    boolean existsByTaskId(String taskId);

    List<BackgroundTaskOutboxEntry> findByStatus(BackgroundTaskStatus status);
}
