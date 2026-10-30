package com.moveinsync.roster.repository;

import com.moveinsync.roster.domain.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {
    List<Booking> findByTenantIdAndEmployeeIdAndStatusAndPickupAtAfter(UUID tenantId, UUID employeeId, String status, Instant now);
    List<Booking> findByTenantIdAndEmployeeId(UUID tenantId, UUID employeeId);
    List<Booking> findByEmployeeId(UUID employeeId);
}


