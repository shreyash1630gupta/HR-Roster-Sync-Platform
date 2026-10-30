package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moveinsync.roster.domain.enums.EventType;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncEventRequest {

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("event_type")
    private EventType eventType;

    @JsonProperty("tenant_id")
    private String tenantId;

    @JsonProperty("external_employee_id")
    private String externalEmployeeId;

    @JsonProperty("source_version")
    private Long sourceVersion;

    private Instant timestamp;

    private EmployeeDataDto data;
}

