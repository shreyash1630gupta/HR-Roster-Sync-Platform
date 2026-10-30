package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponseDto {
    private String type;
    private String title;
    private int status;
    private String detail;
    private String instance;
    private Instant timestamp;
    private List<String> errors;
}

