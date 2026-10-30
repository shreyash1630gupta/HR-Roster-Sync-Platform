package com.moveinsync.roster.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OutboxEventDto {

    private UUID id;

    @JsonProperty("aggregate_type")
    private String aggregateType;

    @JsonProperty("aggregate_id")
    private UUID aggregateId;

    @JsonProperty("event_type")
    private String eventType;

    @JsonProperty("tenant_id")
    private UUID tenantId;

    private Map<String, Object> payload;

    private String status;

    @JsonProperty("retry_count")
    private Integer retryCount;

    @JsonProperty("next_attempt_at")
    private Instant nextAttemptAt;

    @JsonProperty("last_error")
    private String lastError;

    @JsonProperty("created_at")
    private Instant createdAt;

    @JsonProperty("published_at")
    private Instant publishedAt;
}

