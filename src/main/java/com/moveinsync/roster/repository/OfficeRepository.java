package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.Office;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OfficeRepository extends JpaRepository<Office, UUID> {
    Optional<Office> findByTenantIdAndCode(UUID tenantId, String code);
    List<Office> findByTenantIdAndActiveTrue(UUID tenantId);
}

