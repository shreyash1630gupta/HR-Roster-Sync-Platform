package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OperatorDecisionRequestDto {

    @NotBlank(message = "Operator reason is mandatory")
    private String reason;

    @JsonProperty("operator_id")
    private UUID operatorId;
}

