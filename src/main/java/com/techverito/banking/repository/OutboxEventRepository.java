package com.techverito.banking.repository;

import com.techverito.banking.entity.OutboxEvent;
import com.techverito.banking.entity.OutboxEventStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * The rows the relay may send now: {@link OutboxEventStatus#PENDING} and due, ordered by
     * ordering key and then sequence number.
     *
     * <p>The pessimistic lock is taken per row so two relay passes cannot send the same event, and
     * the {@code -2} lock timeout is Hibernate's "skip locked": a row another transaction is
     * already draining is skipped instead of blocking this pass, so one slow subject cannot hold up
     * the others. A broker failure is never a reason to stop selecting a row - it is rescheduled
     * through {@code nextAttemptAt} - and a {@link OutboxEventStatus#FAILED} row is never selected
     * here at all.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            select e from OutboxEvent e
            where e.status = com.techverito.banking.entity.OutboxEventStatus.PENDING
              and e.nextAttemptAt <= :now
            order by e.orderingKey, e.sequenceNo
            """)
    List<OutboxEvent> findPendingForRelay(@Param("now") Instant now);

    /**
     * The unsent events of one ordering key, oldest first. The relay sends only the first of them,
     * which is what stops a later change of the same customer or account overtaking an earlier one.
     */
    @Query("""
            select e from OutboxEvent e
            where e.orderingKey = :orderingKey
              and e.status = com.techverito.banking.entity.OutboxEventStatus.PENDING
            order by e.sequenceNo
            """)
    List<OutboxEvent> findPendingByOrderingKey(@Param("orderingKey") String orderingKey);

    /**
     * The events that could not be published, kept in the database with their event id, attempt
     * count and failure reason for investigation.
     */
    @Query("""
            select e from OutboxEvent e
            where e.status = com.techverito.banking.entity.OutboxEventStatus.FAILED
            order by e.id
            """)
    List<OutboxEvent> findFailed();

    /** The rows in one status, for investigation and reporting across the whole outbox. */
    List<OutboxEvent> findByStatus(OutboxEventStatus status);

    /** The next sequence number of an ordering key, so its events keep the order they happened in. */
    @Query("select coalesce(max(e.sequenceNo), 0) + 1 from OutboxEvent e where e.orderingKey = :orderingKey")
    long nextSequenceNo(@Param("orderingKey") String orderingKey);
}
