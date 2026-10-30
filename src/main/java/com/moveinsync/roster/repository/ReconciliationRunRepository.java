package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.ReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {
    Optional<ReconciliationRun> findByTenantIdAndFileChecksum(UUID tenantId, String fileChecksum);
}

