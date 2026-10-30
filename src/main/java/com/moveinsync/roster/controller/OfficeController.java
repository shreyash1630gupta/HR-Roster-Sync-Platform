package com.moveinsync.roster.controller;

import com.moveinsync.roster.cache.OfficeCacheService;
import com.moveinsync.roster.domain.entity.Office;
import com.moveinsync.roster.service.SyncEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/offices")
@RequiredArgsConstructor
@Tag(name = "Offices", description = "Endpoints for managing office locations and cache invalidation")
public class OfficeController {

    private final OfficeCacheService officeCacheService;
    private final SyncEventService syncEventService;

    @GetMapping("/{code}")
    @Operation(summary = "Get office by code", description = "Retrieves office details, backed by Caffeine cache")
    public ResponseEntity<Office> getOffice(
            @PathVariable("code") String code,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = syncEventService.resolveTenantId(tenantIdHeader);
        Optional<Office> officeOpt = officeCacheService.getOffice(tenantId, code);
        return officeOpt.map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Office not found"));
    }

    @PostMapping
    @Operation(summary = "Create or update office", description = "Saves office and invalidates cached entry")
    public ResponseEntity<Office> createOffice(
            @RequestBody Office office,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = syncEventService.resolveTenantId(tenantIdHeader);
        office.setTenantId(tenantId);
        Office saved = officeCacheService.saveOffice(office);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @DeleteMapping("/{code}")
    @Operation(summary = "Deactivate office", description = "Sets office active = false and evicts it from Caffeine cache")
    public ResponseEntity<Void> deactivateOffice(
            @PathVariable("code") String code,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = syncEventService.resolveTenantId(tenantIdHeader);
        boolean deactivated = officeCacheService.deactivateOffice(tenantId, code);
        if (!deactivated) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Office not found");
        }
        return ResponseEntity.noContent().build();
    }
}

