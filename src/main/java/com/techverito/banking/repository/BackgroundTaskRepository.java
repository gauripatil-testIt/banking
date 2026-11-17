package com.techverito.banking.repository;

import com.techverito.banking.entity.BackgroundTask;
import com.techverito.banking.entity.BackgroundTaskStatus;
import com.techverito.banking.entity.BackgroundTaskType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BackgroundTaskRepository extends JpaRepository<BackgroundTask, Long> {

    /** The task already submitted for one business identity, if there is one. */
    Optional<BackgroundTask> findByTaskTypeAndBusinessKey(BackgroundTaskType taskType, String businessKey);

    /** PENDING rows that have failed before and are due again, oldest first, for the relay. */
    List<BackgroundTask> findTop50ByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
            BackgroundTaskStatus status, Instant now);

    /** PENDING rows that have never been attempted, oldest first, for the relay. */
    List<BackgroundTask> findTop50ByStatusAndNextAttemptAtIsNullOrderByCreatedAtAsc(BackgroundTaskStatus status);
}
