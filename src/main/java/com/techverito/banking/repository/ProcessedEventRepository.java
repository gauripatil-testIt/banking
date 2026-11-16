package com.techverito.banking.repository;

import com.techverito.banking.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    /**
     * True when this event has already been handled, which is what makes a second delivery –
     * a replay, or a duplicate from the broker – a no-op.
     */
    boolean existsByEventId(String eventId);
}
