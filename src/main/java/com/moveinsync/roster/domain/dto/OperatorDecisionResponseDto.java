package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moveinsync.roster.domain.enums.ReconciliationStatus;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorDecisionResponseDto {

    @JsonProperty("run_id")
    private UUID runId;

    private ReconciliationStatus status;

    @JsonProperty("missing_count")
    private Integer missingCount;

    private String message;

    @JsonProperty("decided_at")
    private Instant decidedAt;
}

