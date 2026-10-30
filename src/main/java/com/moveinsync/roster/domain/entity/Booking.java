package com.moveinsync.roster.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "bookings", indexes = {
    @Index(name = "idx_bookings_emp_future", columnList = "employee_id, pickup_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "pickup_at", nullable = false)
    private Instant pickupAt;

    @Column(name = "pickup_location", nullable = false)
    private String pickupLocation;

    @Column(name = "drop_location", nullable = false)
    private String dropLocation;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private String status = "ACTIVE"; // ACTIVE, CANCELLED_EMPLOYEE_EXIT

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

