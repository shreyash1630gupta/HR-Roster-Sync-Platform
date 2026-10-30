package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.dto.EmployeeDetailsDto;
import com.moveinsync.roster.domain.dto.TransportFieldUpdateRequest;
import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeService {

    private final EmployeeRepository employeeRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final FieldOwnershipService fieldOwnershipService;

    @Transactional(readOnly = true)
    public EmployeeDetailsDto getEmployeeById(UUID tenantId, UUID employeeId) {
        Employee employee = employeeRepository.findByTenantIdAndId(tenantId, employeeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        return toDetailsDto(employee);
    }

    @Transactional(readOnly = true)
    public Page<EmployeeDetailsDto> getEmployees(UUID tenantId, EmployeeStatus status, Pageable pageable) {
        Page<Employee> page;
        if (status != null) {
            page = employeeRepository.findByTenantIdAndStatus(tenantId, status, pageable);
        } else {
            page = employeeRepository.findByTenantId(tenantId, pageable);
        }
        return page.map(this::toDetailsDto);
    }

    @Transactional
    public EmployeeDetailsDto updateTransportFields(UUID tenantId, UUID employeeId, TransportFieldUpdateRequest request) {
        Employee employee = employeeRepository.findByTenantIdAndId(tenantId, employeeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        // Update transport fields only
        fieldOwnershipService.applyTransportFields(employee, request);
        employee = employeeRepository.save(employee);

        // Record outbox event for downstream transport sync
        Map<String, Object> payload = new HashMap<>();
        payload.put("employee_id", employee.getId().toString());
        payload.put("tenant_id", tenantId.toString());
        payload.put("pickup_point", employee.getPickupPoint());
        payload.put("drop_point", employee.getDropPoint());
        payload.put("special_assistance_needs", employee.getSpecialAssistanceNeeds());
        payload.put("preferred_cab_type", employee.getPreferredCabType());

        OutboxEvent outbox = OutboxEvent.builder()
                .aggregateType("EMPLOYEE")
                .aggregateId(employee.getId())
                .eventType("EMPLOYEE_TRANSPORT_FIELDS_UPDATED")
                .tenantId(tenantId)
                .payload(payload)
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(Instant.now())
                .build();
        outboxEventRepository.save(outbox);

        log.info("Transport fields updated by Transport Admin: tenantId={}, employeeId={}", tenantId, employeeId);
        return toDetailsDto(employee);
    }

    private EmployeeDetailsDto toDetailsDto(Employee emp) {
        Map<String, Object> hrFields = new LinkedHashMap<>();
        hrFields.put("name", emp.getName());
        hrFields.put("email", emp.getEmail());
        hrFields.put("phone", emp.getPhone());
        hrFields.put("manager_id", emp.getManagerId());
        hrFields.put("office_code", emp.getOfficeCode());
        hrFields.put("shift", emp.getShift());
        hrFields.put("department", emp.getDepartment());
        hrFields.put("designation", emp.getDesignation());

        Map<String, Object> transportFields = new LinkedHashMap<>();
        transportFields.put("pickup_point", emp.getPickupPoint());
        transportFields.put("drop_point", emp.getDropPoint());
        transportFields.put("special_assistance_needs", emp.getSpecialAssistanceNeeds());
        transportFields.put("preferred_cab_type", emp.getPreferredCabType());

        return EmployeeDetailsDto.builder()
                .id(emp.getId())
                .tenantId(emp.getTenantId())
                .externalEmployeeId(emp.getExternalEmployeeId())
                .status(emp.getStatus())
                .currentVersion(emp.getCurrentVersion())
                .hrOwnedFields(hrFields)
                .transportOwnedFields(transportFields)
                .createdAt(emp.getCreatedAt())
                .updatedAt(emp.getUpdatedAt())
                .build();
    }
}

