package com.moveinsync.roster.controller;

import com.moveinsync.roster.domain.dto.OperatorDecisionRequestDto;
import com.moveinsync.roster.domain.dto.OperatorDecisionResponseDto;
import com.moveinsync.roster.domain.dto.ReconciliationDetailsDto;
import com.moveinsync.roster.domain.dto.ReconciliationResponseDto;
import com.moveinsync.roster.service.ReconciliationService;
import com.moveinsync.roster.service.SyncEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Nightly Reconciliation", description = "Endpoints for nightly CSV diffing, safety brake, and operator approval")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;
    private final SyncEventService syncEventService;

    @PostMapping(value = {"/sync/reconcile", "/reconciliation/upload"}, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload nightly employee CSV export", description = "Streams CSV into unlogged staging table and computes set-based SQL diffs")
    @ApiResponse(responseCode = "200", description = "Reconciliation completed or needs approval")
    @ApiResponse(responseCode = "400", description = "Invalid request or missing generated_at")
    @ApiResponse(responseCode = "409", description = "Conflict: Advisory lock held or duplicate file checksum")
    public ResponseEntity<ReconciliationResponseDto> uploadReconciliationCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam("generated_at") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant generatedAt,
            @RequestParam(value = "export_id", required = false) String exportId,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = syncEventService.resolveTenantId(tenantIdHeader);
        ReconciliationResponseDto response = reconciliationService.processReconciliation(tenantId, file, exportId, generatedAt);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/reconciliation/{runId}")
    @Operation(summary = "Get reconciliation run details", description = "Returns breakdown stats, safety brake metadata, and candidates")
    @ApiResponse(responseCode = "200", description = "Reconciliation details found")
    @ApiResponse(responseCode = "404", description = "Run not found")
    public ResponseEntity<ReconciliationDetailsDto> getRunDetails(
            @PathVariable UUID runId,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = syncEventService.resolveTenantId(tenantIdHeader);
        return ResponseEntity.ok(reconciliationService.getRunDetails(runId, tenantId));
    }

    @PostMapping("/reconciliation/{runId}/approve")
    @Operation(summary = "Operator approves safety brake deactivations", description = "Applies pending deactivations and marks run completed (idempotent)")
    @ApiResponse(responseCode = "200", description = "Approved and deactivations executed")
    @ApiResponse(responseCode = "400", description = "Run not in NEEDS_APPROVAL state")
    @ApiResponse(responseCode = "404", description = "Run not found")
    public ResponseEntity<OperatorDecisionResponseDto> approveRun(
            @PathVariable UUID runId,
            @Valid @RequestBody OperatorDecisionRequestDto request,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = syncEventService.resolveTenantId(tenantIdHeader);
        return ResponseEntity.ok(reconciliationService.approveRun(runId, tenantId, request));
    }

    @PostMapping("/reconciliation/{runId}/reject")
    @Operation(summary = "Operator rejects safety brake deactivations", description = "Rejects run with zero employee deactivations")
    @ApiResponse(responseCode = "200", description = "Run rejected")
    @ApiResponse(responseCode = "400", description = "Run not in NEEDS_APPROVAL state")
    @ApiResponse(responseCode = "404", description = "Run not found")
    public ResponseEntity<OperatorDecisionResponseDto> rejectRun(
            @PathVariable UUID runId,
            @Valid @RequestBody OperatorDecisionRequestDto request,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = syncEventService.resolveTenantId(tenantIdHeader);
        return ResponseEntity.ok(reconciliationService.rejectRun(runId, tenantId, request));
    }
}

