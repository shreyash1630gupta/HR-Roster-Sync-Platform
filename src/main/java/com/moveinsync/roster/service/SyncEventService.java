package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.dto.BatchSyncResponse;
import com.moveinsync.roster.domain.dto.EventResultDto;
import com.moveinsync.roster.domain.dto.SyncEventRequest;
import com.moveinsync.roster.domain.entity.Tenant;
import com.moveinsync.roster.domain.enums.ProcessingStatus;
import com.moveinsync.roster.metrics.RosterMetrics;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.validation.EventValidator;
import com.moveinsync.roster.validation.ValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncEventService {

    public static final int MAX_BATCH_SIZE = 1000;

    private final EventValidator eventValidator;
    private final SyncEngine syncEngine;
    private final TenantRepository tenantRepository;
    private final RosterMetrics rosterMetrics;

    public BatchSyncResponse processBatch(List<SyncEventRequest> events) {
        if (events == null) {
            events = Collections.emptyList();
        }

        String batchId = UUID.randomUUID().toString();
        log.info("Starting batch ingestion: batchId={}, size={}", batchId, events.size());

        List<EventResultDto> results = new ArrayList<>(events.size());
        int accepted = 0;
        int rejected = 0;
        int skipped = 0;
        int conflicts = 0;

        for (int i = 0; i < events.size(); i++) {
            SyncEventRequest event = events.get(i);
            EventResultDto result = processSingleEvent(i, event);
            results.add(result);

            if (result.getStatus() == ProcessingStatus.ACCEPTED) {
                accepted++;
            } else if (result.getStatus() == ProcessingStatus.REJECTED) {
                rejected++;
            } else if (result.getStatus() == ProcessingStatus.SKIPPED) {
                skipped++;
            } else if (result.getStatus() == ProcessingStatus.CONFLICT) {
                conflicts++;
            }
        }

        log.info("Batch ingestion completed: batchId={}, total={}, accepted={}, rejected={}, skipped={}, conflicts={}",
                batchId, events.size(), accepted, rejected, skipped, conflicts);

        return BatchSyncResponse.builder()
                .batchId(batchId)
                .total(events.size())
                .accepted(accepted)
                .rejected(rejected)
                .skipped(skipped)
                .conflicts(conflicts)
                .results(results)
                .build();
    }

    public EventResultDto processSingleEvent(int index, SyncEventRequest event) {
        String eventId = event != null ? event.getEventId() : "unknown";
        String extEmpId = event != null ? event.getExternalEmployeeId() : "unknown";
        String eventType = (event != null && event.getEventType() != null) ? event.getEventType().name() : "UNKNOWN";

        if (rosterMetrics != null) {
            rosterMetrics.recordEventReceived(eventType);
        }

        long startNanos = System.nanoTime();

        // Step 1: Boundary validation
        ValidationResult valResult = eventValidator.validate(event);
        if (!valResult.isValid()) {
            log.warn("Event rejected at boundary: eventId={}, employeeId={}, errors={}",
                    eventId, extEmpId, valResult.getErrors());
            if (rosterMetrics != null) {
                rosterMetrics.recordEventRejected("VALIDATION_FAILED");
            }
            return EventResultDto.builder()
                    .index(index)
                    .eventId(eventId)
                    .externalEmployeeId(extEmpId)
                    .status(ProcessingStatus.REJECTED)
                    .errors(valResult.getErrors())
                    .build();
        }

        // Step 2: Delegate to SyncEngine (executed in fresh transaction with retry proxy)
        try {
            UUID tenantId = resolveTenantId(event.getTenantId());
            EventResultDto result = syncEngine.processEventInTransaction(index, event, tenantId);

            if (rosterMetrics != null) {
                if (result.getStatus() == ProcessingStatus.ACCEPTED) {
                    rosterMetrics.recordEventAccepted(eventType);
                } else if (result.getStatus() == ProcessingStatus.SKIPPED) {
                    rosterMetrics.recordEventSkipped(result.getAction());
                } else if (result.getStatus() == ProcessingStatus.CONFLICT) {
                    rosterMetrics.recordVersionConflict();
                } else if (result.getStatus() == ProcessingStatus.REJECTED) {
                    rosterMetrics.recordEventRejected(result.getAction());
                }
                rosterMetrics.recordEventProcessingTime(Duration.ofNanos(System.nanoTime() - startNanos));
            }

            return result;
        } catch (Exception ex) {
            log.error("Failed to process event: eventId={}, employeeId={}", eventId, extEmpId, ex);
            if (rosterMetrics != null) {
                rosterMetrics.recordEventRejected("INTERNAL_ERROR");
            }
            return EventResultDto.builder()
                    .index(index)
                    .eventId(eventId)
                    .externalEmployeeId(extEmpId)
                    .status(ProcessingStatus.REJECTED)
                    .errors(List.of("Internal processing error"))
                    .build();
        }
    }

    public UUID resolveTenantId(String tenantIdOrCode) {
        if (tenantIdOrCode == null || tenantIdOrCode.isBlank()) {
            throw new IllegalArgumentException("Tenant identifier is required and cannot be null or blank");
        }
        try {
            return UUID.fromString(tenantIdOrCode);
        } catch (IllegalArgumentException e) {
            return tenantRepository.findByCode(tenantIdOrCode)
                    .map(Tenant::getId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown tenant: " + tenantIdOrCode));
        }
    }
}
