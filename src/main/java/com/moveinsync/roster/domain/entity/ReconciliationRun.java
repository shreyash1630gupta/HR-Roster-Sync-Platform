package com.moveinsync.roster.domain.entity;

import com.moveinsync.roster.domain.enums.ReconciliationStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_runs", uniqueConstraints = {
    @UniqueConstraint(name = "uq_recon_tenant_checksum", columnNames = {"tenant_id", "file_checksum"})
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "file_checksum", nullable = false, length = 64)
    private String fileChecksum;

    @Column(name = "export_id", length = 100)
    private String exportId;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ReconciliationStatus status = ReconciliationStatus.RECEIVED;

    @Builder.Default
    @Column(name = "total_in_export", nullable = false)
    private Integer totalInExport = 0;

    @Builder.Default
    @Column(name = "total_active_in_db", nullable = false)
    private Integer totalActiveInDb = 0;

    @Builder.Default
    @Column(name = "new_count", nullable = false)
    private Integer newCount = 0;

    @Builder.Default
    @Column(name = "updated_count", nullable = false)
    private Integer updatedCount = 0;

    @Builder.Default
    @Column(name = "unchanged_count", nullable = false)
    private Integer unchangedCount = 0;

    @Builder.Default
    @Column(name = "skipped_newer_count", nullable = false)
    private Integer skippedNewerCount = 0;

    @Builder.Default
    @Column(name = "missing_count", nullable = false)
    private Integer missingCount = 0;

    @Builder.Default
    @Column(name = "missing_ratio", nullable = false, precision = 5, scale = 4)
    private BigDecimal missingRatio = BigDecimal.ZERO;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "operator_reason", columnDefinition = "TEXT")
    private String operatorReason;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}

