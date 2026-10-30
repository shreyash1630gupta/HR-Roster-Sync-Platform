package com.moveinsync.roster.domain.entity;

import com.moveinsync.roster.domain.enums.CandidateType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reconciliation_candidates", indexes = {
    @Index(name = "idx_recon_candidates_run", columnList = "run_id, candidate_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationCandidate {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "external_employee_id", nullable = false, length = 64)
    private String externalEmployeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_type", nullable = false, length = 32)
    private CandidateType candidateType;

    @Builder.Default
    @Column(name = "disposition", nullable = false, length = 32)
    private String disposition = "PENDING"; // PENDING, APPLIED, SKIPPED_NEWER, SKIPPED_BRAKE

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

