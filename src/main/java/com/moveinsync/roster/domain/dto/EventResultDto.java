package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.moveinsync.roster.domain.enums.ProcessingStatus;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventResultDto {

    private Integer index;

    @JsonProperty("event_id")
    private String eventId;

    @JsonProperty("external_employee_id")
    private String externalEmployeeId;

    private ProcessingStatus status;

    private String action;

    private String reason;

    private List<String> errors;
}

