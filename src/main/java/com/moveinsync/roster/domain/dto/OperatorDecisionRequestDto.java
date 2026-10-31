package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.*;

import java.nio.charset.StandardCharsets;
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

    @JsonProperty("operator_id")
    public void setOperatorId(Object rawId) {
        if (rawId == null) {
            this.operatorId = UUID.randomUUID();
        } else if (rawId instanceof UUID u) {
            this.operatorId = u;
        } else {
            String str = rawId.toString();
            try {
                this.operatorId = UUID.fromString(str);
            } catch (IllegalArgumentException e) {
                this.operatorId = UUID.nameUUIDFromBytes(str.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}

