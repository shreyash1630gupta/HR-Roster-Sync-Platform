package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {
    Optional<ProcessedEvent> findByEventId(String eventId);
    Optional<ProcessedEvent> findByTenantIdAndExternalEmployeeIdAndSourceVersion(UUID tenantId, String externalEmployeeId, Long sourceVersion);
}

