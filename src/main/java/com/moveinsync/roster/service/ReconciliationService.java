package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.dto.*;
import com.moveinsync.roster.domain.entity.*;
import com.moveinsync.roster.domain.enums.CandidateType;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.EventType;
import com.moveinsync.roster.domain.enums.ReconciliationStatus;
import com.moveinsync.roster.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {

    public static final double SAFETY_BRAKE_THRESHOLD = 0.30;

    private final ReconciliationRunRepository reconciliationRunRepository;
    private final ReconciliationCandidateRepository reconciliationCandidateRepository;
    private final EmployeeRepository employeeRepository;
    private final OutboxEventRepository outboxEventRepository;
    private final TenantAdvisoryLockService advisoryLockService;
    private final StagingTableManager stagingTableManager;
    private final SyncEngine syncEngine;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Executes the nightly reconciliation process against an uploaded CSV export.
     */
    public ReconciliationResponseDto processReconciliation(
            UUID tenantId, MultipartFile file, String exportId, Instant generatedAt
    ) {
        if (generatedAt == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export must include generated_at timestamp for safe reconciliation");
        }
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Export CSV file is required");
        }

        // 1. Acquire Tenant Advisory Lock
        if (!advisoryLockService.tryAcquire(tenantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Reconciliation already in progress for tenant");
        }

        UUID runId = UUID.randomUUID();
        String stagingTable = stagingTableManager.getTableName(runId);

        try {
            // 2. Stream CSV into Staging & Compute Checksum
            StagingTableManager.StagingLoadResult loadResult;
            try (InputStream is = file.getInputStream()) {
                loadResult = stagingTableManager.streamCsvIntoStaging(is, tenantId, stagingTable);
            }

            String fileChecksum = loadResult.fileChecksum;

            // 3. Duplicate File Checksum Guard
            if (reconciliationRunRepository.findByTenantIdAndFileChecksum(tenantId, fileChecksum).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate export file already processed");
            }

            // 4. Initialize Reconciliation Run
            ReconciliationRun run = ReconciliationRun.builder()
                    .id(runId)
                    .tenantId(tenantId)
                    .fileChecksum(fileChecksum)
                    .exportId(exportId != null ? exportId : "EXP-" + System.currentTimeMillis())
                    .generatedAt(generatedAt)
                    .status(ReconciliationStatus.RUNNING)
                    .totalInExport(loadResult.validRows)
                    .startedAt(Instant.now())
                    .build();
            run = reconciliationRunRepository.save(run);

            // 5. Execute Database-Side Set Diff
            executeReconciliationDiff(run, stagingTable, tenantId, generatedAt);

            return ReconciliationResponseDto.builder()
                    .runId(run.getId())
                    .status(run.getStatus())
                    .message(run.getStatus() == ReconciliationStatus.NEEDS_APPROVAL
                            ? "Safety brake triggered: missing ratio exceeds 30%. Operator approval required."
                            : "Reconciliation completed successfully.")
                    .build();

        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            log.error("Reconciliation execution failed for tenantId={}: {}", tenantId, ex.getMessage(), ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Reconciliation processing failure: " + ex.getMessage());
        } finally {
            stagingTableManager.dropStagingTable(stagingTable);
            advisoryLockService.release(tenantId);
        }
    }

    private void executeReconciliationDiff(ReconciliationRun run, String stagingTable, UUID tenantId, Instant generatedAt) {
        // Query Active Count Before Recon
        Integer totalActiveInDb = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM employees WHERE tenant_id = ? AND status = 'ACTIVE'",
                Integer.class, tenantId
        );
        if (totalActiveInDb == null) totalActiveInDb = 0;
        run.setTotalActiveInDb(totalActiveInDb);

        // 1. DISCOVER NEW EMPLOYEES
        String newSql = String.format("""
            SELECT s.external_employee_id, s.name, s.email, s.phone, s.manager_id,
                   s.office_code, s.shift, s.department, s.designation, s.source_version, s.record_hash
            FROM %s s
            LEFT JOIN employees e ON s.tenant_id = e.tenant_id AND s.external_employee_id = e.external_employee_id
            WHERE s.validation_status = 'VALID' AND e.id IS NULL
            """, stagingTable);

        List<Map<String, Object>> newRows = jdbcTemplate.queryForList(newSql);
        run.setNewCount(newRows.size());

        for (Map<String, Object> r : newRows) {
            applyNewEmployeeFromStaging(tenantId, r);
            recordCandidate(run.getId(), (String) r.get("external_employee_id"), CandidateType.NEW, "APPLIED");
        }

        // 2. DISCOVER UNCHANGED EMPLOYEES
        String unchangedSql = String.format("""
            SELECT COUNT(*)
            FROM %s s
            JOIN employees e ON s.tenant_id = e.tenant_id AND s.external_employee_id = e.external_employee_id
            WHERE s.validation_status = 'VALID' AND e.status = 'ACTIVE' AND s.record_hash = e.record_hash
            """, stagingTable);

        Integer unchangedCount = jdbcTemplate.queryForObject(unchangedSql, Integer.class);
        run.setUnchangedCount(unchangedCount != null ? unchangedCount : 0);

        // 3. DISCOVER CHANGED EMPLOYEES
        String changedSql = String.format("""
            SELECT s.external_employee_id, s.name, s.email, s.phone, s.manager_id,
                   s.office_code, s.shift, s.department, s.designation, s.source_version, s.record_hash,
                   e.id AS emp_id, e.current_version, e.updated_at
            FROM %s s
            JOIN employees e ON s.tenant_id = e.tenant_id AND s.external_employee_id = e.external_employee_id
            WHERE s.validation_status = 'VALID' AND e.status = 'ACTIVE' AND s.record_hash != e.record_hash
            """, stagingTable);

        List<Map<String, Object>> changedRows = jdbcTemplate.queryForList(changedSql);
        int updatedCount = 0;
        int skippedNewerCount = 0;

        for (Map<String, Object> r : changedRows) {
            String extEmpId = (String) r.get("external_employee_id");
            Long fileVersion = r.get("source_version") != null ? ((Number) r.get("source_version")).longValue() : null;
            long dbVersion = ((Number) r.get("current_version")).longValue();
            Timestamp dbUpdatedAtTs = (Timestamp) r.get("updated_at");
            Instant dbUpdatedAt = dbUpdatedAtTs.toInstant();

            // Snapshot consistency check
            boolean shouldApply;
            if (fileVersion != null) {
                shouldApply = (fileVersion > dbVersion);
            } else {
                shouldApply = dbUpdatedAt.isBefore(generatedAt);
            }

            if (shouldApply) {
                applyChangedEmployeeFromStaging(tenantId, r, fileVersion != null ? fileVersion : dbVersion + 1);
                updatedCount++;
                recordCandidate(run.getId(), extEmpId, CandidateType.CHANGED, "APPLIED");
            } else {
                log.info("Snapshot watermark: skipping export change for empId={} (DB updated after export: {} >= {})",
                        extEmpId, dbUpdatedAt, generatedAt);
                skippedNewerCount++;
                recordCandidate(run.getId(), extEmpId, CandidateType.CHANGED, "SKIPPED_NEWER");
            }
        }
        run.setUpdatedCount(updatedCount);

        // 4. DISCOVER MISSING EMPLOYEES
        String missingSql = String.format("""
            SELECT e.id, e.external_employee_id, e.updated_at
            FROM employees e
            LEFT JOIN %s s ON e.tenant_id = s.tenant_id AND e.external_employee_id = s.external_employee_id
            WHERE e.tenant_id = ? AND e.status = 'ACTIVE' AND s.external_employee_id IS NULL
            """, stagingTable);

        List<Map<String, Object>> missingRows = jdbcTemplate.queryForList(missingSql, tenantId);
        List<Map<String, Object>> eligibleMissing = new ArrayList<>();

        for (Map<String, Object> r : missingRows) {
            String extEmpId = (String) r.get("external_employee_id");
            Timestamp dbUpdatedAtTs = (Timestamp) r.get("updated_at");
            Instant dbUpdatedAt = dbUpdatedAtTs.toInstant();

            // Watermark check: did employee get updated AFTER export generated?
            if (!dbUpdatedAt.isBefore(generatedAt)) {
                log.info("Watermark guard: missing employee {} updated after export ({} >= {}), skipping deactivation",
                        extEmpId, dbUpdatedAt, generatedAt);
                skippedNewerCount++;
            } else {
                eligibleMissing.add(r);
            }
        }

        run.setSkippedNewerCount(skippedNewerCount);
        run.setMissingCount(eligibleMissing.size());

        // 5. SAFETY BRAKE EVALUATION
        BigDecimal missingRatio = BigDecimal.ZERO;
        if (totalActiveInDb > 0 && !eligibleMissing.isEmpty()) {
            missingRatio = BigDecimal.valueOf((double) eligibleMissing.size() / totalActiveInDb)
                    .setScale(4, RoundingMode.HALF_UP);
        }
        run.setMissingRatio(missingRatio);

        boolean emptyFileBrake = (run.getTotalInExport() == 0 && totalActiveInDb > 0);
        boolean ratioBrake = (eligibleMissing.size() > 0 && missingRatio.doubleValue() >= SAFETY_BRAKE_THRESHOLD);

        if (emptyFileBrake || ratioBrake) {
            log.warn("SAFETY BRAKE FIRED for runId={}, tenantId={}: missingCount={}, activeInDb={}, ratio={}",
                    run.getId(), tenantId, eligibleMissing.size(), totalActiveInDb, missingRatio);

            for (Map<String, Object> r : eligibleMissing) {
                recordCandidate(run.getId(), (String) r.get("external_employee_id"), CandidateType.MISSING, "PENDING");
            }

            run.setStatus(ReconciliationStatus.NEEDS_APPROVAL);
            run.setOperatorReason("Safety brake triggered: missing ratio " + missingRatio + " >= 0.30");
        } else {
            // Apply deactivations automatically
            for (Map<String, Object> r : eligibleMissing) {
                UUID empId = (UUID) r.get("id");
                String extEmpId = (String) r.get("external_employee_id");
                deactivateEmployee(empId, tenantId, extEmpId);
                recordCandidate(run.getId(), extEmpId, CandidateType.MISSING, "APPLIED");
            }
            run.setStatus(ReconciliationStatus.COMPLETED);
            run.setCompletedAt(Instant.now());
        }

        reconciliationRunRepository.save(run);
    }

    private void applyNewEmployeeFromStaging(UUID tenantId, Map<String, Object> r) {
        String extEmpId = (String) r.get("external_employee_id");
        Long version = r.get("source_version") != null ? ((Number) r.get("source_version")).longValue() : 1L;
        String hash = (String) r.get("record_hash");

        Employee emp = Employee.builder()
                .tenantId(tenantId)
                .externalEmployeeId(extEmpId)
                .name((String) r.get("name"))
                .email((String) r.get("email"))
                .phone((String) r.get("phone"))
                .managerId((String) r.get("manager_id"))
                .officeCode((String) r.get("office_code"))
                .shift((String) r.get("shift"))
                .department((String) r.get("department"))
                .designation((String) r.get("designation"))
                .status(EmployeeStatus.ACTIVE)
                .currentVersion(version)
                .lastPayloadHash(hash)
                .recordHash(hash)
                .pickupPoint(null)
                .specialAssistanceNeeds(null)
                .dropPoint(null)
                .preferredCabType(null)
                .jpaVersion(0)
                .build();

        emp = employeeRepository.save(emp);

        // Record outbox event
        recordOutboxEvent(emp.getId(), tenantId, "EMPLOYEE_CREATED", extEmpId, emp.getOfficeCode(), version);
    }

    private void applyChangedEmployeeFromStaging(UUID tenantId, Map<String, Object> r, long version) {
        UUID empId = (UUID) r.get("emp_id");
        String extEmpId = (String) r.get("external_employee_id");
        String hash = (String) r.get("record_hash");

        Optional<Employee> empOpt = employeeRepository.findById(empId);
        if (empOpt.isPresent()) {
            Employee emp = empOpt.get();
            emp.setName((String) r.get("name"));
            emp.setEmail((String) r.get("email"));
            emp.setPhone((String) r.get("phone"));
            emp.setManagerId((String) r.get("manager_id"));
            emp.setOfficeCode((String) r.get("office_code"));
            emp.setShift((String) r.get("shift"));
            emp.setDepartment((String) r.get("department"));
            emp.setDesignation((String) r.get("designation"));
            emp.setStatus(EmployeeStatus.ACTIVE);
            emp.setCurrentVersion(version);
            emp.setLastPayloadHash(hash);
            emp.setRecordHash(hash);
            employeeRepository.save(emp);

            recordOutboxEvent(emp.getId(), tenantId, "EMPLOYEE_UPDATED", extEmpId, emp.getOfficeCode(), version);
        }
    }

    private void deactivateEmployee(UUID employeeId, UUID tenantId, String extEmpId) {
        Optional<Employee> empOpt = employeeRepository.findById(employeeId);
        if (empOpt.isPresent()) {
            Employee emp = empOpt.get();
            emp.setStatus(EmployeeStatus.INACTIVE);
            emp.setCurrentVersion(emp.getCurrentVersion() + 1);
            employeeRepository.save(emp);

            recordOutboxEvent(employeeId, tenantId, "EMPLOYEE_DEACTIVATED", extEmpId, emp.getOfficeCode(), emp.getCurrentVersion());
        }
    }

    private void recordOutboxEvent(UUID aggId, UUID tenantId, String eventType, String extEmpId, String officeCode, long version) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("employee_id", aggId.toString());
        payload.put("external_employee_id", extEmpId);
        payload.put("tenant_id", tenantId.toString());
        payload.put("office_code", officeCode);
        payload.put("version", version);

        OutboxEvent outbox = OutboxEvent.builder()
                .aggregateType("EMPLOYEE")
                .aggregateId(aggId)
                .eventType(eventType)
                .tenantId(tenantId)
                .payload(payload)
                .status("PENDING")
                .retryCount(0)
                .nextAttemptAt(Instant.now())
                .build();
        outboxEventRepository.save(outbox);
    }

    private void recordCandidate(UUID runId, String extEmpId, CandidateType type, String disposition) {
        ReconciliationCandidate candidate = ReconciliationCandidate.builder()
                .runId(runId)
                .externalEmployeeId(extEmpId)
                .candidateType(type)
                .disposition(disposition)
                .build();
        reconciliationCandidateRepository.save(candidate);
    }

    // -------------------------------------------------------------
    // OPERATOR APPROVAL & REJECTION WORKFLOWS
    // -------------------------------------------------------------

    @Transactional
    public OperatorDecisionResponseDto approveRun(UUID runId, UUID tenantId, OperatorDecisionRequestDto request) {
        ReconciliationRun run = reconciliationRunRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reconciliation run not found"));

        if (!run.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
        }

        // Idempotent approval check
        if (run.getStatus() == ReconciliationStatus.COMPLETED) {
            log.info("Run {} already completed, returning idempotent success", runId);
            return OperatorDecisionResponseDto.builder()
                    .runId(runId)
                    .status(ReconciliationStatus.COMPLETED)
                    .missingCount(run.getMissingCount())
                    .message("Run was already approved and completed.")
                    .decidedAt(run.getCompletedAt())
                    .build();
        }

        if (run.getStatus() != ReconciliationStatus.NEEDS_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Run is not in NEEDS_APPROVAL state: " + run.getStatus());
        }

        // Apply missing deactivations
        List<ReconciliationCandidate> candidates = reconciliationCandidateRepository
                .findByRunIdAndCandidateType(runId, CandidateType.MISSING);

        for (ReconciliationCandidate c : candidates) {
            if ("PENDING".equals(c.getDisposition())) {
                Optional<Employee> empOpt = employeeRepository
                        .findByTenantIdAndExternalEmployeeId(tenantId, c.getExternalEmployeeId());
                empOpt.ifPresent(e -> deactivateEmployee(e.getId(), tenantId, e.getExternalEmployeeId()));
                c.setDisposition("APPLIED");
                reconciliationCandidateRepository.save(c);
            }
        }

        run.setStatus(ReconciliationStatus.COMPLETED);
        run.setApprovedBy(request.getOperatorId());
        run.setApprovedAt(Instant.now());
        run.setOperatorReason(request.getReason());
        run.setCompletedAt(Instant.now());
        reconciliationRunRepository.save(run);

        log.info("Operator approved reconciliation run {}: {} missing deactivated", runId, candidates.size());

        return OperatorDecisionResponseDto.builder()
                .runId(runId)
                .status(ReconciliationStatus.COMPLETED)
                .missingCount(candidates.size())
                .message("Deactivations approved and applied.")
                .decidedAt(run.getApprovedAt())
                .build();
    }

    @Transactional
    public OperatorDecisionResponseDto rejectRun(UUID runId, UUID tenantId, OperatorDecisionRequestDto request) {
        ReconciliationRun run = reconciliationRunRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reconciliation run not found"));

        if (!run.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
        }

        if (run.getStatus() != ReconciliationStatus.NEEDS_APPROVAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Run is not in NEEDS_APPROVAL state: " + run.getStatus());
        }

        List<ReconciliationCandidate> candidates = reconciliationCandidateRepository
                .findByRunIdAndCandidateType(runId, CandidateType.MISSING);

        for (ReconciliationCandidate c : candidates) {
            c.setDisposition("SKIPPED_BRAKE");
            reconciliationCandidateRepository.save(c);
        }

        run.setStatus(ReconciliationStatus.REJECTED);
        run.setOperatorReason(request.getReason());
        run.setCompletedAt(Instant.now());
        reconciliationRunRepository.save(run);

        log.info("Operator rejected reconciliation run {}: zero employees deactivated", runId);

        return OperatorDecisionResponseDto.builder()
                .runId(runId)
                .status(ReconciliationStatus.REJECTED)
                .missingCount(0)
                .message("Reconciliation run rejected. Zero employee records deactivated.")
                .decidedAt(run.getCompletedAt())
                .build();
    }

    @Transactional(readOnly = true)
    public ReconciliationDetailsDto getRunDetails(UUID runId, UUID tenantId) {
        ReconciliationRun run = reconciliationRunRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Reconciliation run not found"));

        if (!run.getTenantId().equals(tenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant mismatch");
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total_in_export", run.getTotalInExport());
        stats.put("total_active_in_db", run.getTotalActiveInDb());
        stats.put("new_count", run.getNewCount());
        stats.put("updated_count", run.getUpdatedCount());
        stats.put("unchanged_count", run.getUnchangedCount());
        stats.put("skipped_newer_count", run.getSkippedNewerCount());
        stats.put("missing_count", run.getMissingCount());
        stats.put("missing_ratio", run.getMissingRatio());

        boolean brakeTriggered = run.getStatus() == ReconciliationStatus.NEEDS_APPROVAL;

        return ReconciliationDetailsDto.builder()
                .runId(run.getId())
                .tenantId(run.getTenantId())
                .status(run.getStatus())
                .exportId(run.getExportId())
                .generatedAt(run.getGeneratedAt())
                .startedAt(run.getStartedAt())
                .completedAt(run.getCompletedAt())
                .stats(stats)
                .safetyBrakeTriggered(brakeTriggered)
                .safetyBrakeThreshold(BigDecimal.valueOf(SAFETY_BRAKE_THRESHOLD))
                .operatorActionRequired(brakeTriggered ? "Operator approval required due to missing ratio exceeding 30%" : null)
                .operatorReason(run.getOperatorReason())
                .approvedAt(run.getApprovedAt())
                .build();
    }
}

