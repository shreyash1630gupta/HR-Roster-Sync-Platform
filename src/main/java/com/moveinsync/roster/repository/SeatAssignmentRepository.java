package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.SeatAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface SeatAssignmentRepository extends JpaRepository<SeatAssignment, UUID> {
    List<SeatAssignment> findByTenantIdAndEmployeeIdAndStatus(UUID tenantId, UUID employeeId, String status);
    List<SeatAssignment> findByTenantIdAndEmployeeId(UUID tenantId, UUID employeeId);
    List<SeatAssignment> findByEmployeeId(UUID employeeId);
}


