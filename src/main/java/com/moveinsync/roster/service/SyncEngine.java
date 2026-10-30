package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.dto.EmployeeDataDto;
import com.moveinsync.roster.domain.dto.EventResultDto;
import com.moveinsync.roster.domain.dto.SyncEventRequest;
import com.moveinsync.roster.domain.entity.Employee;
import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.domain.entity.ProcessedEvent;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.EventType;
import com.moveinsync.roster.domain.enums.ProcessingStatus;
import com.moveinsync.roster.repository.EmployeeRepository;
import com.moveinsync.roster.repository.OutboxEventRepository;
import com.moveinsync.roster.repository.ProcessedEventRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncEngine {

    private final EmployeeRepository employeeRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final FieldOwnershipService fieldOwnershipService;

    /**
     * Executes single event ingestion with 2-level idempotency, version ordering,
     * and optimistic locking retry inside an isolated transaction.
     */
    @Retryable(
            retryFor = {
                    OptimisticLockException.class,
                    ObjectOptimisticLockingFailureException.class,
                    DataIntegrityViolationException.class
            },
            maxAttempts = 3,
            backoff = @Backoff(delay = 50, multiplier = 2)
    )
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public EventResultDto processEventInTransaction(int index, SyncEventRequest event, UUID tenantId) {
        String eventId = event.getEventId();
        String extEmpId = event.getExternalEmployeeId();

        // -------------------------------------------------------------
        // LEVEL 1 IDEMPOTENCY: Check Event ID (Repeated Delivery Detection)
        // -------------------------------------------------------------
        Optional<ProcessedEvent> existingEventOpt = processedEventRepository.findByEventId(eventId);
        if (existingEventOpt.isPresent()) {
            ProcessedEvent existing = existingEventOpt.get();
            log.info("Level 1 Idempotency: repeated delivery detected for eventId={}, returning cached status={}",
                    eventId, existing.getResult());

            return EventResultDto.builder()
                    .index(index)
                    .eventId(eventId)
                    .externalEmployeeId(extEmpId)
                    .status(existing.getResult())
                    .action(existing.getAction())
                    .reason("DUPLICATE_EVENT_ID")
                    .build();
        }

        // -------------------------------------------------------------
        // LEVEL 2 IDEMPOTENCY & VERSION ORDERING: Check Employee State
        // -------------------------------------------------------------
        Optional<Employee> empOpt = employeeRepository.findByTenantIdAndExternalEmployeeId(tenantId, extEmpId);

        if (empOpt.isEmpty()) {
            return handleNewEmployee(index, event, tenantId);
        } else {
            return handleExistingEmployee(index, event, tenantId, empOpt.get());
        }
    }

    private EventResultDto handleNewEmployee(int index, SyncEventRequest event, UUID tenantId) {
        String eventId = event.getEventId();
        String extEmpId = event.getExternalEmployeeId();

        if (event.getEventType() != EventType.CREATE) {
            log.warn("Non-CREATE event received for non-existent employee: eventId={}, employeeId={}, type={}",
                    eventId, extEmpId, event.getEventType());

            recordProcessedEvent(eventId, tenantId, extEmpId, event.getSourceVersion(),
                    "", event.getEventType(), ProcessingStatus.REJECTED, null, "EMPLOYEE_NOT_FOUND");

            return EventResultDto.builder()
                    .index(index)
                    .eventId(eventId)
                    .externalEmployeeId(extEmpId)
                    .status(ProcessingStatus.REJECTED)
                    .errors(List.of("Employee not found for " + event.getEventType()))
                    .build();
        }

        // Sanitize HR payload: Transport fields stripped
        EmployeeDataDto data = event.getData();
        fieldOwnershipService.sanitizeHrPayload(data);
        String payloadHash = computePayloadHash(data);

        try {
            Employee employee = Employee.builder()
                    .tenantId(tenantId)
                    .externalEmployeeId(extEmpId)
                    .currentVersion(event.getSourceVersion())
                    .lastPayloadHash(payloadHash)
                    .recordHash(payloadHash)
                    .status(EmployeeStatus.ACTIVE)
                    .name(data.getName())
                    .email(data.getEmail())
                    .phone(data.getPhone())
                    .managerId(data.getManagerId())
                    .officeCode(data.getOfficeCode())
                    .shift(data.getShift())
                    .department(data.getDepartment())
                    .designation(data.getDesignation())
                    .pickupPoint(null)
                    .specialAssistanceNeeds(null)
                    .dropPoint(null)
                    .preferredCabType(null)
                    .jpaVersion(0)
                    .build();

            employee = employeeRepository.saveAndFlush(employee);

            // Record Outbox Event
            recordOutboxEvent(employee.getId(), tenantId, "EMPLOYEE_CREATED", buildOutboxPayload(employee));

            // Record Processed Event Ledger
            recordProcessedEvent(eventId, tenantId, extEmpId, event.getSourceVersion(),
                    payloadHash, EventType.CREATE, ProcessingStatus.ACCEPTED, "CREATED", null);

            log.info("Employee created: tenantId={}, employeeId={}, version={}",
                    tenantId, extEmpId, event.getSourceVersion());

            return EventResultDto.builder()
                    .index(index)
                    .eventId(eventId)
                    .externalEmployeeId(extEmpId)
                    .status(ProcessingStatus.ACCEPTED)
                    .action("CREATED")
                    .build();

        } catch (DataIntegrityViolationException ex) {
            // Concurrent CREATE race condition: another thread inserted first
            log.info("Concurrent CREATE detected for employeeId={}, falling back to version ordering", extEmpId);
            Optional<Employee> existing = employeeRepository.findByTenantIdAndExternalEmployeeId(tenantId, extEmpId);
            if (existing.isPresent()) {
                return handleExistingEmployee(index, event, tenantId, existing.get());
            }
            throw ex;
        }
    }

    private EventResultDto handleExistingEmployee(int index, SyncEventRequest event, UUID tenantId, Employee employee) {
        String eventId = event.getEventId();
        String extEmpId = event.getExternalEmployeeId();
        long incomingVersion = event.getSourceVersion();
        long currentVersion = employee.getCurrentVersion();

        // 1. Stale Version
        if (incomingVersion < currentVersion) {
            log.info("Stale event ignored: eventId={}, employeeId={}, incomingVersion={}, currentVersion={}",
                    eventId, extEmpId, incomingVersion, currentVersion);

            recordProcessedEvent(eventId, tenantId, extEmpId, incomingVersion,
                    "", event.getEventType(), ProcessingStatus.SKIPPED, null, "STALE_VERSION");

            return EventResultDto.builder()
                    .index(index)
                    .eventId(eventId)
                    .externalEmployeeId(extEmpId)
                    .status(ProcessingStatus.SKIPPED)
                    .reason("STALE_VERSION")
                    .build();
        }

        // 2. Same Version: Check for Conflict vs Duplicate
        if (incomingVersion == currentVersion) {
            String incomingHash = event.getData() != null ? computePayloadHash(event.getData()) : "";
            if (incomingHash.equals(employee.getLastPayloadHash())) {
                log.info("Duplicate revision ignored: eventId={}, employeeId={}, version={}",
                        eventId, extEmpId, incomingVersion);

                recordProcessedEvent(eventId, tenantId, extEmpId, incomingVersion,
                        incomingHash, event.getEventType(), ProcessingStatus.SKIPPED, null, "DUPLICATE_REVISION");

                return EventResultDto.builder()
                        .index(index)
                        .eventId(eventId)
                        .externalEmployeeId(extEmpId)
                        .status(ProcessingStatus.SKIPPED)
                        .reason("DUPLICATE_REVISION")
                        .build();
            } else {
                log.warn("VERSION_CONFLICT quarantined: eventId={}, employeeId={}, version={}, hashMismatch",
                        eventId, extEmpId, incomingVersion);

                recordProcessedEvent(eventId, tenantId, extEmpId, incomingVersion,
                        incomingHash, event.getEventType(), ProcessingStatus.CONFLICT, null, "VERSION_CONFLICT");

                return EventResultDto.builder()
                        .index(index)
                        .eventId(eventId)
                        .externalEmployeeId(extEmpId)
                        .status(ProcessingStatus.CONFLICT)
                        .reason("VERSION_CONFLICT")
                        .build();
            }
        }

        // 3. Higher Version: Forward Progress
        if (event.getEventType() == EventType.DEACTIVATE) {
            employee.setStatus(EmployeeStatus.INACTIVE);
            employee.setCurrentVersion(incomingVersion);
            employeeRepository.save(employee);

            recordOutboxEvent(employee.getId(), tenantId, "EMPLOYEE_DEACTIVATED", buildOutboxPayload(employee));

            recordProcessedEvent(eventId, tenantId, extEmpId, incomingVersion,
                    "", EventType.DEACTIVATE, ProcessingStatus.ACCEPTED, "DEACTIVATED", null);

            log.info("Employee deactivated: tenantId={}, employeeId={}, version={}",
                    tenantId, extEmpId, incomingVersion);

            return EventResultDto.builder()
                    .index(index)
                    .eventId(eventId)
                    .externalEmployeeId(extEmpId)
                    .status(ProcessingStatus.ACCEPTED)
                    .action("DEACTIVATED")
                    .build();

        } else {
            // CREATE or UPDATE with higher version
            EmployeeDataDto data = event.getData();
            fieldOwnershipService.sanitizeHrPayload(data);
            String incomingHash = computePayloadHash(data);

            // Apply HR-owned fields only (Transport fields preserved untouched)
            employee.setName(data.getName());
            employee.setEmail(data.getEmail());
            employee.setPhone(data.getPhone());
            employee.setManagerId(data.getManagerId());
            employee.setOfficeCode(data.getOfficeCode());
            employee.setShift(data.getShift());
            employee.setDepartment(data.getDepartment());
            employee.setDesignation(data.getDesignation());
            employee.setStatus(EmployeeStatus.ACTIVE);
            employee.setCurrentVersion(incomingVersion);
            employee.setLastPayloadHash(incomingHash);
            employee.setRecordHash(incomingHash);

            employeeRepository.save(employee);

            recordOutboxEvent(employee.getId(), tenantId, "EMPLOYEE_UPDATED", buildOutboxPayload(employee));

            recordProcessedEvent(eventId, tenantId, extEmpId, incomingVersion,
                    incomingHash, event.getEventType(), ProcessingStatus.ACCEPTED, "UPDATED", null);

            log.info("Employee updated: tenantId={}, employeeId={}, version={}",
                    tenantId, extEmpId, incomingVersion);

            return EventResultDto.builder()
                    .index(index)
                    .eventId(eventId)
                    .externalEmployeeId(extEmpId)
                    .status(ProcessingStatus.ACCEPTED)
                    .action("UPDATED")
                    .build();
        }
    }

    private void recordProcessedEvent(String eventId, UUID tenantId, String extEmpId,
                                      long version, String payloadHash, EventType type,
                                      ProcessingStatus result, String action, String skipReason) {
        if (version > 0 && processedEventRepository.findByTenantIdAndExternalEmployeeIdAndSourceVersion(tenantId, extEmpId, version).isPresent()) {
            log.info("ProcessedEvent revision already recorded for empId={}, version={}, skipping duplicate insert", extEmpId, version);
            return;
        }

        try {
            ProcessedEvent pe = ProcessedEvent.builder()
                    .eventId(eventId)
                    .tenantId(tenantId)
                    .externalEmployeeId(extEmpId)
                    .sourceVersion(version)
                    .payloadHash(payloadHash != null ? payloadHash : "")
                    .eventType(type)
                    .result(result)
                    .action(action)
                    .skipReason(skipReason)
                    .build();
            processedEventRepository.save(pe);
        } catch (DataIntegrityViolationException ex) {
            log.info("ProcessedEvent unique constraint caught: eventId={}, empVersion={}", eventId, version);
        }
    }

    private void recordOutboxEvent(UUID aggregateId, UUID tenantId, String eventType, Map<String, Object> payload) {
        OutboxEvent outbox = OutboxEvent.builder()
                .aggregateType("EMPLOYEE")
                .aggregateId(aggregateId)
                .eventType(eventType)
                .tenantId(tenantId)
                .payload(payload)
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(Instant.now())
                .build();
        outboxEventRepository.save(outbox);
    }

    private Map<String, Object> buildOutboxPayload(Employee emp) {
        Map<String, Object> map = new HashMap<>();
        map.put("employee_id", emp.getId().toString());
        map.put("external_employee_id", emp.getExternalEmployeeId());
        map.put("tenant_id", emp.getTenantId().toString());
        map.put("status", emp.getStatus().name());
        map.put("version", emp.getCurrentVersion());
        map.put("office_code", emp.getOfficeCode());
        return map;
    }

    public String computePayloadHash(EmployeeDataDto data) {
        if (data == null) return "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String raw = String.join("|",
                Objects.toString(data.getName(), ""),
                Objects.toString(data.getEmail(), ""),
                Objects.toString(data.getPhone(), ""),
                Objects.toString(data.getManagerId(), ""),
                Objects.toString(data.getOfficeCode(), ""),
                Objects.toString(data.getShift(), ""),
                Objects.toString(data.getDepartment(), ""),
                Objects.toString(data.getDesignation(), "")
        );
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm missing", e);
        }
    }
}

