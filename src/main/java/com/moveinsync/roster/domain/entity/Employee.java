package com.moveinsync.roster.domain.entity;

import com.moveinsync.roster.domain.enums.EmployeeStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "employees", uniqueConstraints = {
    @UniqueConstraint(name = "uq_emp_tenant_ext", columnNames = {"tenant_id", "external_employee_id"}),
    @UniqueConstraint(name = "uq_emp_tenant_id", columnNames = {"tenant_id", "id"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "external_employee_id", nullable = false, length = 64)
    private String externalEmployeeId;

    @Builder.Default
    @Column(name = "current_version", nullable = false)
    private Long currentVersion = 1L;

    @Column(name = "last_payload_hash", nullable = false, length = 64)
    private String lastPayloadHash;

    @Column(name = "record_hash", nullable = false, length = 64)
    private String recordHash;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private EmployeeStatus status = EmployeeStatus.ACTIVE;

    // HR-owned fields (Core per case study §II)
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "manager_id", length = 64)
    private String managerId;

    @Column(name = "office_code", nullable = false, length = 64)
    private String officeCode;

    // HR-owned fields (Extended assumptions per §18)
    @Column(name = "phone", length = 32)
    private String phone;

    @Column(name = "shift", length = 64)
    private String shift;

    @Column(name = "department", length = 100)
    private String department;

    @Column(name = "designation", length = 100)
    private String designation;

    // Transport-owned fields (Core per case study §II)
    @Column(name = "pickup_point")
    private String pickupPoint;

    @Column(name = "special_assistance_needs", columnDefinition = "TEXT")
    private String specialAssistanceNeeds;

    // Transport-owned fields (Extended assumptions per §18)
    @Column(name = "drop_point")
    private String dropPoint;

    @Column(name = "preferred_cab_type", length = 64)
    private String preferredCabType;

    // Optimistic Concurrency Control
    @Version
    @Builder.Default
    @Column(name = "jpa_version", nullable = false)
    private Integer jpaVersion = 0;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
