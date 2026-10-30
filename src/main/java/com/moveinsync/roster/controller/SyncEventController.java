package com.moveinsync.roster.controller;

import com.moveinsync.roster.domain.dto.BatchSyncRequest;
import com.moveinsync.roster.domain.dto.BatchSyncResponse;
import com.moveinsync.roster.domain.dto.SyncEventRequest;
import com.moveinsync.roster.service.SyncEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/api/v1/sync")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Sync Events", description = "Endpoints for ingesting real-time HR roster sync events")
public class SyncEventController {

    private final SyncEventService syncEventService;

    @PostMapping("/events")
    @Operation(summary = "Ingest real-time HR events", description = "Accepts a batch of up to 1,000 employee CREATE, UPDATE, or DEACTIVATE events")
    @ApiResponse(responseCode = "200", description = "Batch processed with per-record results")
    @ApiResponse(responseCode = "413", description = "Batch size exceeds maximum limit of 1,000 events")
    @ApiResponse(responseCode = "503", description = "Database unavailable - client should retry")
    public ResponseEntity<BatchSyncResponse> ingestEvents(@RequestBody(required = false) BatchSyncRequest request) {
        List<SyncEventRequest> events = (request != null && request.getEvents() != null)
                ? request.getEvents()
                : Collections.emptyList();

        if (events.size() > SyncEventService.MAX_BATCH_SIZE) {
            log.warn("Batch size ceiling exceeded: requested={}, max={}", events.size(), SyncEventService.MAX_BATCH_SIZE);
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Batch size exceeds maximum limit of " + SyncEventService.MAX_BATCH_SIZE + " events"
            );
        }

        BatchSyncResponse response = syncEventService.processBatch(events);
        return ResponseEntity.ok(response);
    }
}

