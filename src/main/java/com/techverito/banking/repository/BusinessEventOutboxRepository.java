package com.techverito.banking.repository;

import com.techverito.banking.entity.BusinessEventOutboxEntry;
import com.techverito.banking.entity.OutboxEventStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface BusinessEventOutboxRepository extends JpaRepository<BusinessEventOutboxEntry, Long> {

    /**
     * The rows the drain may publish now, oldest first, so "the order they happened"
     * is the id order.
     */
    List<BusinessEventOutboxEntry> findByStatusAndNextAttemptAtLessThanEqualOrderByIdAsc(
            OutboxEventStatus status, Instant now, Pageable page);

    /**
     * True when an earlier row of the same business key is still unresolved, in which
     * case this row has to wait so a key's events keep their order.
     */
    boolean existsByIdLessThanAndBusinessKeyAndStatus(Long id, String businessKey, OutboxEventStatus status);

    boolean existsByEventId(String eventId);

    List<BusinessEventOutboxEntry> findByBusinessKeyOrderByIdAsc(String businessKey);
}
