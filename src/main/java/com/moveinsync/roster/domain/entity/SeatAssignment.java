package com.moveinsync.roster.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "seat_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "employee_id", nullable = false)
    private UUID employeeId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "cab_id", nullable = false, length = 64)
    private String cabId;

    @Column(name = "seat_number", nullable = false)
    private Integer seatNumber;

    @Builder.Default
    @Column(name = "status", nullable = false, length = 32)
    private String status = "ASSIGNED"; // ASSIGNED, FREED

    @Column(name = "freed_at")
    private Instant freedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

