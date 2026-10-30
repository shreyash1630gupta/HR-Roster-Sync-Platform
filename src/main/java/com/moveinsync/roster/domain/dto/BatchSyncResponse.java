package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchSyncResponse {

    @JsonProperty("batch_id")
    private String batchId;

    private int total;
    private int accepted;
    private int rejected;
    private int skipped;
    private int conflicts;

    private List<EventResultDto> results;
}

