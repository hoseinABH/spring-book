package com.flashsale.seat;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface SeatRepository extends JpaRepository<Seat, UUID> {

    /**
     * Acquires exclusive row-level write locks (SELECT ... FOR UPDATE) on the requested seats.
     * Crucial: ORDER BY s.id ASC ensures deterministic lock acquisition ordering across transactions,
     * preventing deadlocks if concurrent requests lock overlapping seats in reverse order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000")})
    @Query("SELECT s FROM Seat s WHERE s.id IN :seatIds ORDER BY s.id ASC")
    List<Seat> findByIdsWithPessimisticLock(@Param("seatIds") List<UUID> seatIds);

    /**
     * Efficient query for full venue seat map hydration.
     */
    List<Seat> findByEventIdOrderBySectionAscRowLabelAscSeatNumberAsc(UUID eventId);

    /**
     * Background Reaper query: Uses PostgreSQL 'FOR UPDATE SKIP LOCKED' to claim expired holds.
     * Backed by partial index: idx_seats_reaper (hold_expires_at) WHERE status = 'HELD'.
     * SKIP LOCKED guarantees multiple worker threads/pods never block each other or cause contention.
     */
    @Query(value = """
        SELECT * FROM seats
        WHERE status = 'HELD' AND hold_expires_at <= :now
        ORDER BY hold_expires_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT :limit
        """, nativeQuery = true)
    List<Seat> findExpiredHoldsForReclaim(@Param("now") Instant now, @Param("limit") int limit);

    long countByEventIdAndStatus(UUID eventId, SeatStatus status);
}
