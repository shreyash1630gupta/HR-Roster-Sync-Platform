package com.moveinsync.roster.controller;

import com.moveinsync.roster.domain.dto.OutboxEventDto;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.service.OutboxPoller;
import com.moveinsync.roster.service.SyncEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/outbox")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Transactional Outbox & DLQ", description = "Endpoints for operator DLQ inspection and event replay")
public class OutboxController {

    private final OutboxPoller outboxPoller;
    private final SyncEventService syncEventService;

    @GetMapping("/dead-letter")
    @Operation(summary = "List dead-letter outbox events", description = "Retrieves all events in DEAD_LETTER status for the tenant")
    @ApiResponse(responseCode = "200", description = "List of dead-letter events")
    public ResponseEntity<List<OutboxEventDto>> getDeadLetterEvents(
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = syncEventService.resolveTenantId(tenantIdHeader);
        List<OutboxEvent> events = outboxPoller.getDeadLetterEvents(tenantId);
        List<OutboxEventDto> dtos = events.stream().map(this::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(dtos);
    }

    @PostMapping("/{eventId}/replay")
    @Operation(summary = "Replay dead-letter outbox event", description = "Resets a dead-letter event to PENDING for immediate dispatch retry")
    @ApiResponse(responseCode = "200", description = "Event reset to PENDING")
    @ApiResponse(responseCode = "404", description = "Outbox event not found")
    @ApiResponse(responseCode = "400", description = "Event is not in DEAD_LETTER status")
    public ResponseEntity<OutboxEventDto> replayEvent(
            @PathVariable("eventId") UUID eventId,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = syncEventService.resolveTenantId(tenantIdHeader);
        OutboxEvent replayed = outboxPoller.replayDeadLetterEvent(eventId, tenantId);
        return ResponseEntity.ok(toDto(replayed));
    }

    private OutboxEventDto toDto(OutboxEvent e) {
        return OutboxEventDto.builder()
                .id(e.getId())
                .aggregateType(e.getAggregateType())
                .aggregateId(e.getAggregateId())
                .eventType(e.getEventType())
                .tenantId(e.getTenantId())
                .payload(e.getPayload())
                .status(e.getStatus())
                .retryCount(e.getRetryCount())
                .nextAttemptAt(e.getNextAttemptAt())
                .lastError(e.getLastError())
                .createdAt(e.getCreatedAt())
                .publishedAt(e.getPublishedAt())
                .build();
    }
}

