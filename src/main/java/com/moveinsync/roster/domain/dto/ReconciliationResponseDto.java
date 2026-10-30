package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moveinsync.roster.domain.enums.ReconciliationStatus;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReconciliationResponseDto {

    @JsonProperty("run_id")
    private UUID runId;

    private ReconciliationStatus status;

    private String message;
}

