package com.moveinsync.roster.controller;

import com.moveinsync.roster.domain.dto.EmployeeDetailsDto;
import com.moveinsync.roster.domain.dto.TransportFieldUpdateRequest;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.service.EmployeeService;
import com.moveinsync.roster.service.SyncEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/employees")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Employees", description = "Endpoints for employee details and Transport Admin field updates")
public class EmployeeController {

    private final EmployeeService employeeService;
    private final SyncEventService syncEventService;

    @GetMapping("/{id}")
    @Operation(summary = "Get employee details", description = "Returns employee profile with clearly segregated HR-owned and Transport-owned fields")
    @ApiResponse(responseCode = "200", description = "Employee profile found")
    @ApiResponse(responseCode = "404", description = "Employee not found")
    public ResponseEntity<EmployeeDetailsDto> getEmployee(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = resolveTenantId(tenantIdHeader);
        return ResponseEntity.ok(employeeService.getEmployeeById(tenantId, id));
    }

    @GetMapping
    @Operation(summary = "List employees (paginated)", description = "Returns paginated list of employees for tenant")
    public ResponseEntity<Page<EmployeeDetailsDto>> listEmployees(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) EmployeeStatus status,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = resolveTenantId(tenantIdHeader);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return ResponseEntity.ok(employeeService.getEmployees(tenantId, status, pageable));
    }

    @PutMapping("/{id}/transport-fields")
    @Operation(summary = "Update transport-owned fields", description = "Transport Admin updates pickup point, drop point, and special assistance needs")
    @ApiResponse(responseCode = "200", description = "Transport fields successfully updated")
    @ApiResponse(responseCode = "403", description = "Forbidden: Cannot modify HR-owned fields")
    @ApiResponse(responseCode = "404", description = "Employee not found")
    public ResponseEntity<EmployeeDetailsDto> updateTransportFields(
            @PathVariable UUID id,
            @RequestBody TransportFieldUpdateRequest request,
            @RequestHeader(value = "X-Tenant-ID", defaultValue = "11111111-1111-1111-1111-111111111111") String tenantIdHeader
    ) {
        UUID tenantId = resolveTenantId(tenantIdHeader);
        EmployeeDetailsDto updated = employeeService.updateTransportFields(tenantId, id, request);
        return ResponseEntity.ok(updated);
    }

    private UUID resolveTenantId(String tenantIdHeader) {
        UUID contextTenantId = com.moveinsync.roster.security.TenantContextHolder.getTenantId();
        if (contextTenantId != null) {
            return contextTenantId;
        }
        return syncEventService.resolveTenantId(tenantIdHeader);
    }
}

