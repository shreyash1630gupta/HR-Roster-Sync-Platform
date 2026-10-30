package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import lombok.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeDetailsDto {

    private UUID id;

    @JsonProperty("tenant_id")
    private UUID tenantId;

    @JsonProperty("external_employee_id")
    private String externalEmployeeId;

    private EmployeeStatus status;

    @JsonProperty("current_version")
    private Long currentVersion;

    @JsonProperty("hr_owned_fields")
    private Map<String, Object> hrOwnedFields;

    @JsonProperty("transport_owned_fields")
    private Map<String, Object> transportOwnedFields;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("updated_at")
    private Instant updatedAt;
}

