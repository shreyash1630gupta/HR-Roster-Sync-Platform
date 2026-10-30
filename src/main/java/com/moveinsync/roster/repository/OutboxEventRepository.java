package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    @Query(value = "SELECT * FROM outbox_events WHERE status = 'PENDING' AND next_attempt_at <= :now ORDER BY created_at ASC FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<OutboxEvent> findPendingEventsForPublishing(@Param("now") Instant now, Pageable pageable);

    long countByStatus(String status);

    List<OutboxEvent> findByStatus(String status);

    List<OutboxEvent> findByAggregateId(UUID aggregateId);

    List<OutboxEvent> findByTenantId(UUID tenantId);

    List<OutboxEvent> findByTenantIdAndStatus(UUID tenantId, String status);
}


