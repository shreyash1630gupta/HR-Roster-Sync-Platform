package com.moveinsync.roster.domain.entity;

import com.moveinsync.roster.domain.enums.EventType;
import com.moveinsync.roster.domain.enums.ProcessingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "processed_events", uniqueConstraints = {
    @UniqueConstraint(name = "uq_processed_event_id", columnNames = {"event_id"}),
    @UniqueConstraint(name = "uq_processed_emp_version", columnNames = {"tenant_id", "external_employee_id", "source_version"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "external_employee_id", nullable = false, length = 64)
    private String externalEmployeeId;

    @Column(name = "source_version", nullable = false)
    private Long sourceVersion;

    @Column(name = "payload_hash", nullable = false, length = 64)
    private String payloadHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 32)
    private ProcessingStatus result;

    @Column(name = "action", length = 32)
    private String action;

    @Column(name = "skip_reason", length = 64)
    private String skipReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_errors")
    private List<String> validationErrors;

    @CreationTimestamp
    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;
}
