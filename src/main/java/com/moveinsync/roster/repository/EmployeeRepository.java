package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmployeeRepository extends JpaRepository<Employee, UUID> {
    Optional<Employee> findByTenantIdAndExternalEmployeeId(UUID tenantId, String externalEmployeeId);
    Optional<Employee> findByTenantIdAndId(UUID tenantId, UUID id);
    Page<Employee> findByTenantId(UUID tenantId, Pageable pageable);
    Page<Employee> findByTenantIdAndStatus(UUID tenantId, EmployeeStatus status, Pageable pageable);
    long countByTenantIdAndStatus(UUID tenantId, EmployeeStatus status);
}

