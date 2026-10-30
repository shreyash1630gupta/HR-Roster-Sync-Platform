package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.ProcessedOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProcessedOutboxEventRepository extends JpaRepository<ProcessedOutboxEvent, UUID> {
    Optional<ProcessedOutboxEvent> findByOutboxEventId(UUID outboxEventId);
    boolean existsByOutboxEventId(UUID outboxEventId);
}


