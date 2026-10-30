package com.moveinsync.roster.domain.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BatchSyncRequest {
    private List<SyncEventRequest> events;
}

