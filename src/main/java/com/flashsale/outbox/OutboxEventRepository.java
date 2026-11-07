package com.flashsale.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Polls the oldest unprocessed outbox events using PostgreSQL 'FOR UPDATE SKIP LOCKED'.
     * Backed by partial index: idx_outbox_unprocessed (created_at) WHERE processed = FALSE.
     * Guarantees multiple outbox relay worker instances never process the same event concurrently.
     */
    @Query(value = """
        SELECT * FROM outbox_events
        WHERE processed = FALSE
        ORDER BY created_at ASC
        FOR UPDATE SKIP LOCKED
        LIMIT :limit
        """, nativeQuery = true)
    List<OutboxEvent> findUnprocessedEventsForRelay(@Param("limit") int limit);
}
