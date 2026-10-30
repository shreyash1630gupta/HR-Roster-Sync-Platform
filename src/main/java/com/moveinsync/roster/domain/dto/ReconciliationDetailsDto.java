package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moveinsync.roster.domain.enums.ReconciliationStatus;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationDetailsDto {

    @JsonProperty("run_id")
    private UUID runId;

    @JsonProperty("tenant_id")
    private UUID tenantId;

    private ReconciliationStatus status;

    @JsonProperty("export_id")
    private String exportId;

    @JsonProperty("generated_at")
    private Instant generatedAt;

    @JsonProperty("started_at")
    private Instant startedAt;

    @JsonProperty("completed_at")
    private Instant completedAt;

    private Map<String, Object> stats;

    @JsonProperty("safety_brake_triggered")
    private boolean safetyBrakeTriggered;

    @JsonProperty("safety_brake_threshold")
    private BigDecimal safetyBrakeThreshold;

    @JsonProperty("operator_action_required")
    private String operatorActionRequired;

    @JsonProperty("operator_reason")
    private String operatorReason;

    @JsonProperty("approved_at")
    private Instant approvedAt;
}

