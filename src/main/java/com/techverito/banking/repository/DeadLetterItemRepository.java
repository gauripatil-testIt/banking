package com.techverito.banking.repository;

import com.techverito.banking.entity.DeadLetterEntry;
import com.techverito.banking.entity.DeadLetterStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeadLetterItemRepository extends JpaRepository<DeadLetterEntry, Long> {

    /**
     * The item this source already set aside under this id, which is what a repeated
     * exhaustion is answered with instead of a second row.
     */
    Optional<DeadLetterEntry> findFirstBySourceAndSourceId(String source, String sourceId);

    /**
     * Every dead-lettered item, oldest first, which is the order operations review them in.
     */
    List<DeadLetterEntry> findAllByOrderByIdAsc();

    /**
     * The items that are still waiting to be replayed, oldest first.
     */
    List<DeadLetterEntry> findByStatusOrderByIdAsc(DeadLetterStatus status);

    /**
     * True when this source already set aside an item with this id, so a repeated
     * exhaustion stores nothing rather than a second copy.
     */
    boolean existsBySourceAndSourceId(String source, String sourceId);
}
