package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.dto.OperatorDecisionRequestDto;
import com.moveinsync.roster.domain.dto.OperatorDecisionResponseDto;
import com.moveinsync.roster.domain.dto.ReconciliationDetailsDto;
import com.moveinsync.roster.domain.dto.ReconciliationResponseDto;
import com.moveinsync.roster.domain.entity.*;
import com.moveinsync.roster.domain.enums.CandidateType;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.domain.enums.ReconciliationStatus;
import com.moveinsync.roster.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationService Unit & Logic Tests (Phase 3)")
class ReconciliationServiceTest {

    @Mock
    private ReconciliationRunRepository reconciliationRunRepository;

    @Mock
    private ReconciliationCandidateRepository reconciliationCandidateRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private TenantAdvisoryLockService advisoryLockService;

    @Mock
    private StagingTableManager stagingTableManager;

    @Mock
    private SyncEngine syncEngine;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private ReconciliationService reconciliationService;

    private UUID tenantId;
    private Instant exportGeneratedAt;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        exportGeneratedAt = Instant.now().minus(30, ChronoUnit.MINUTES);
    }

    @Test
    @DisplayName("IT-3.10: Re-uploading exact same CSV file is rejected with HTTP 409 Conflict")
    void shouldRejectDuplicateFileChecksum() throws Exception {
        byte[] content = "dummy-csv".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "export.csv", "text/csv", content);

        when(advisoryLockService.tryAcquire(tenantId)).thenReturn(true);
        when(stagingTableManager.getTableName(any())).thenReturn("recon_staging_test");

        StagingTableManager.StagingLoadResult loadResult = new StagingTableManager.StagingLoadResult();
        loadResult.fileChecksum = "abc123checksum";
        loadResult.validRows = 10;
        when(stagingTableManager.streamCsvIntoStaging(any(), eq(tenantId), anyString())).thenReturn(loadResult);

        // Simulate file already in database
        when(reconciliationRunRepository.findByTenantIdAndFileChecksum(tenantId, "abc123checksum"))
                .thenReturn(Optional.of(new ReconciliationRun()));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                reconciliationService.processReconciliation(tenantId, file, "EXP-1", exportGeneratedAt)
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Duplicate export file already processed"));
        verify(advisoryLockService).release(tenantId);
    }

    @Test
    @DisplayName("IT-3.11: Concurrent reconciliation upload for same tenant is rejected with HTTP 409 Conflict")
    void shouldRejectWhenTenantLockHeld() {
        MockMultipartFile file = new MockMultipartFile("file", "test.csv", "text/csv", "data".getBytes());
        when(advisoryLockService.tryAcquire(tenantId)).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                reconciliationService.processReconciliation(tenantId, file, "EXP-1", exportGeneratedAt)
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Reconciliation already in progress for tenant"));
    }

    @Test
    @DisplayName("IT-3.2: Missing ratio >= 30% triggers Safety Brake (NEEDS_APPROVAL)")
    void shouldTriggerSafetyBrakeWhenMissingRatioExceedsThirtyPercent() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "export.csv", "text/csv", "test".getBytes());

        when(advisoryLockService.tryAcquire(tenantId)).thenReturn(true);
        when(stagingTableManager.getTableName(any())).thenReturn("recon_staging_test");

        StagingTableManager.StagingLoadResult loadResult = new StagingTableManager.StagingLoadResult();
        loadResult.fileChecksum = "checksum-brake";
        loadResult.validRows = 60;
        when(stagingTableManager.streamCsvIntoStaging(any(), eq(tenantId), anyString())).thenReturn(loadResult);

        when(reconciliationRunRepository.findByTenantIdAndFileChecksum(tenantId, "checksum-brake"))
                .thenReturn(Optional.empty());

        when(reconciliationRunRepository.save(any(ReconciliationRun.class))).thenAnswer(i -> i.getArgument(0));

        // 100 active in DB, 40 missing from export (40% missing)
        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM employees"), eq(Integer.class), eq(tenantId)))
                .thenReturn(100);

        // 0 new, 60 unchanged, 0 changed
        when(jdbcTemplate.queryForList(contains("WHERE s.validation_status = 'VALID' AND e.id IS NULL")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("s.record_hash = e.record_hash"), eq(Integer.class)))
                .thenReturn(60);
        when(jdbcTemplate.queryForList(contains("s.record_hash != e.record_hash")))
                .thenReturn(Collections.emptyList());

        // 40 missing employees
        List<Map<String, Object>> missingRows = new ArrayList<>();
        Instant beforeExport = exportGeneratedAt.minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < 40; i++) {
            Map<String, Object> r = new HashMap<>();
            r.put("id", UUID.randomUUID());
            r.put("external_employee_id", "EMP-MISSING-" + i);
            r.put("updated_at", Timestamp.from(beforeExport));
            missingRows.add(r);
        }
        when(jdbcTemplate.queryForList(contains("s.external_employee_id IS NULL"), eq(tenantId)))
                .thenReturn(missingRows);

        ReconciliationResponseDto response = reconciliationService.processReconciliation(tenantId, file, "EXP-1", exportGeneratedAt);

        assertNotNull(response);
        assertEquals(ReconciliationStatus.NEEDS_APPROVAL, response.getStatus());
        assertTrue(response.getMessage().contains("Safety brake triggered"));

        // Verify missing candidates saved with PENDING disposition
        verify(reconciliationCandidateRepository, times(40)).save(any(ReconciliationCandidate.class));
        // Verify ZERO employees deactivated automatically
        verify(employeeRepository, never()).save(any());
        verify(advisoryLockService).release(tenantId);
    }

    @Test
    @DisplayName("IT-3.3: Upload 0-row empty CSV file — safety brake fires unconditionally (NEEDS_APPROVAL)")
    void shouldTriggerSafetyBrakeOnZeroRowEmptyCsvFile() throws Exception {
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty_export.csv", "text/csv", new byte[0]);

        when(advisoryLockService.tryAcquire(tenantId)).thenReturn(true);
        when(stagingTableManager.getTableName(any())).thenReturn("recon_staging_empty");

        StagingTableManager.StagingLoadResult loadResult = new StagingTableManager.StagingLoadResult();
        loadResult.fileChecksum = "checksum-empty-file";
        loadResult.validRows = 0;
        loadResult.totalRows = 0;
        loadResult.tableName = "recon_staging_empty";

        when(stagingTableManager.streamCsvIntoStaging(any(), eq(tenantId), anyString())).thenReturn(loadResult);
        when(reconciliationRunRepository.findByTenantIdAndFileChecksum(tenantId, "checksum-empty-file"))
                .thenReturn(Optional.empty());
        when(reconciliationRunRepository.save(any(ReconciliationRun.class))).thenAnswer(i -> i.getArgument(0));

        // 50 active in DB, 0 in export -> totalInExport = 0 and totalActiveInDb > 0
        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM employees"), eq(Integer.class), eq(tenantId)))
                .thenReturn(50);
        when(jdbcTemplate.queryForList(contains("WHERE s.validation_status = 'VALID' AND e.id IS NULL")))
                .thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("s.record_hash = e.record_hash"), eq(Integer.class)))
                .thenReturn(0);
        when(jdbcTemplate.queryForList(contains("s.record_hash != e.record_hash")))
                .thenReturn(Collections.emptyList());

        List<Map<String, Object>> missingRows = new ArrayList<>();
        Instant beforeExport = exportGeneratedAt.minus(1, ChronoUnit.HOURS);
        for (int i = 0; i < 50; i++) {
            Map<String, Object> r = new HashMap<>();
            r.put("id", UUID.randomUUID());
            r.put("external_employee_id", "EMP-ACTIVE-" + i);
            r.put("updated_at", Timestamp.from(beforeExport));
            missingRows.add(r);
        }
        when(jdbcTemplate.queryForList(contains("s.external_employee_id IS NULL"), eq(tenantId)))
                .thenReturn(missingRows);

        ReconciliationResponseDto response = reconciliationService.processReconciliation(tenantId, emptyFile, "EXP-EMPTY", exportGeneratedAt);

        assertNotNull(response);
        assertEquals(ReconciliationStatus.NEEDS_APPROVAL, response.getStatus());
        assertTrue(response.getMessage().contains("Safety brake triggered"));

        // Verify zero employee records deactivated
        verify(employeeRepository, never()).save(any());
        verify(advisoryLockService).release(tenantId);
    }

    @Test
    @DisplayName("IT-3.7: Missing ratio < 30% automatically deactivates without brake (COMPLETED)")
    void shouldAutoDeactivateWhenMissingRatioBelowThirtyPercent() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "export.csv", "text/csv", "test".getBytes());

        when(advisoryLockService.tryAcquire(tenantId)).thenReturn(true);
        when(stagingTableManager.getTableName(any())).thenReturn("recon_staging_test");

        StagingTableManager.StagingLoadResult loadResult = new StagingTableManager.StagingLoadResult();
        loadResult.fileChecksum = "checksum-autodeact";
        loadResult.validRows = 95;
        when(stagingTableManager.streamCsvIntoStaging(any(), eq(tenantId), anyString())).thenReturn(loadResult);
        when(reconciliationRunRepository.findByTenantIdAndFileChecksum(tenantId, "checksum-autodeact")).thenReturn(Optional.empty());
        when(reconciliationRunRepository.save(any(ReconciliationRun.class))).thenAnswer(i -> i.getArgument(0));

        // 100 active in DB, 5 missing (5% missing < 30%)
        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM employees"), eq(Integer.class), eq(tenantId))).thenReturn(100);
        when(jdbcTemplate.queryForList(contains("WHERE s.validation_status = 'VALID' AND e.id IS NULL"))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("s.record_hash = e.record_hash"), eq(Integer.class))).thenReturn(95);
        when(jdbcTemplate.queryForList(contains("s.record_hash != e.record_hash"))).thenReturn(Collections.emptyList());

        UUID missingId = UUID.randomUUID();
        Employee missingEmp = Employee.builder()
                .id(missingId)
                .tenantId(tenantId)
                .externalEmployeeId("EMP-MISSING-1")
                .status(EmployeeStatus.ACTIVE)
                .currentVersion(3L)
                .officeCode("BLR-EC")
                .build();
        when(employeeRepository.findById(missingId)).thenReturn(Optional.of(missingEmp));

        Map<String, Object> r = new HashMap<>();
        r.put("id", missingId);
        r.put("external_employee_id", "EMP-MISSING-1");
        r.put("updated_at", Timestamp.from(exportGeneratedAt.minus(1, ChronoUnit.HOURS)));
        when(jdbcTemplate.queryForList(contains("s.external_employee_id IS NULL"), eq(tenantId)))
                .thenReturn(List.of(r));

        ReconciliationResponseDto response = reconciliationService.processReconciliation(tenantId, file, "EXP-1", exportGeneratedAt);

        assertEquals(ReconciliationStatus.COMPLETED, response.getStatus());
        assertEquals(EmployeeStatus.INACTIVE, missingEmp.getStatus());
        assertEquals(4L, missingEmp.getCurrentVersion());

        verify(outboxEventRepository).save(any(OutboxEvent.class));
        verify(advisoryLockService).release(tenantId);
    }

    @Test
    @DisplayName("IT-3.8: Snapshot Test - DB employee has newer data (v8) than export (v5) - export skipped")
    void shouldSkipExportWhenDbHasNewerData() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "export.csv", "text/csv", "test".getBytes());

        when(advisoryLockService.tryAcquire(tenantId)).thenReturn(true);
        when(stagingTableManager.getTableName(any())).thenReturn("recon_staging_test");

        StagingTableManager.StagingLoadResult loadResult = new StagingTableManager.StagingLoadResult();
        loadResult.fileChecksum = "checksum-snap";
        loadResult.validRows = 1;
        when(stagingTableManager.streamCsvIntoStaging(any(), eq(tenantId), anyString())).thenReturn(loadResult);
        when(reconciliationRunRepository.findByTenantIdAndFileChecksum(tenantId, "checksum-snap")).thenReturn(Optional.empty());
        when(reconciliationRunRepository.save(any(ReconciliationRun.class))).thenAnswer(i -> i.getArgument(0));

        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM employees"), eq(Integer.class), eq(tenantId))).thenReturn(1);
        when(jdbcTemplate.queryForList(contains("WHERE s.validation_status = 'VALID' AND e.id IS NULL"))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("s.record_hash = e.record_hash"), eq(Integer.class))).thenReturn(0);
        when(jdbcTemplate.queryForList(contains("s.external_employee_id IS NULL"), eq(tenantId))).thenReturn(Collections.emptyList());

        // 1 Changed row: Export has v5, DB has v8
        Map<String, Object> changedRow = new HashMap<>();
        changedRow.put("emp_id", UUID.randomUUID());
        changedRow.put("external_employee_id", "EMP-RAHUL");
        changedRow.put("source_version", 5L); // Export version 5
        changedRow.put("current_version", 8L); // DB current version 8
        changedRow.put("updated_at", Timestamp.from(exportGeneratedAt.plus(15, ChronoUnit.MINUTES))); // updated at 10:15
        changedRow.put("record_hash", "newhash");

        when(jdbcTemplate.queryForList(contains("s.record_hash != e.record_hash")))
                .thenReturn(List.of(changedRow));

        ReconciliationResponseDto response = reconciliationService.processReconciliation(tenantId, file, "EXP-1", exportGeneratedAt);

        assertEquals(ReconciliationStatus.COMPLETED, response.getStatus());

        // Verify DB employee was NOT overwritten with old export v5 data
        verify(employeeRepository, never()).save(any());
        verify(reconciliationCandidateRepository).save(argThat(c ->
                "SKIPPED_NEWER".equals(c.getDisposition()) && "EMP-RAHUL".equals(c.getExternalEmployeeId())
        ));
    }

    @Test
    @DisplayName("IT-3.9: Watermark Test - Missing from export but updated in DB after export generation - skipped from deactivation")
    void shouldNotDeactivateWhenUpdatedAfterExportGenerated() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "export.csv", "text/csv", "test".getBytes());

        when(advisoryLockService.tryAcquire(tenantId)).thenReturn(true);
        when(stagingTableManager.getTableName(any())).thenReturn("recon_staging_test");

        StagingTableManager.StagingLoadResult loadResult = new StagingTableManager.StagingLoadResult();
        loadResult.fileChecksum = "checksum-watermark";
        loadResult.validRows = 10;
        when(stagingTableManager.streamCsvIntoStaging(any(), eq(tenantId), anyString())).thenReturn(loadResult);
        when(reconciliationRunRepository.findByTenantIdAndFileChecksum(tenantId, "checksum-watermark")).thenReturn(Optional.empty());
        when(reconciliationRunRepository.save(any(ReconciliationRun.class))).thenAnswer(i -> i.getArgument(0));

        when(jdbcTemplate.queryForObject(contains("COUNT(*) FROM employees"), eq(Integer.class), eq(tenantId))).thenReturn(10);
        when(jdbcTemplate.queryForList(contains("WHERE s.validation_status = 'VALID' AND e.id IS NULL"))).thenReturn(Collections.emptyList());
        when(jdbcTemplate.queryForObject(contains("s.record_hash = e.record_hash"), eq(Integer.class))).thenReturn(10);
        when(jdbcTemplate.queryForList(contains("s.record_hash != e.record_hash"))).thenReturn(Collections.emptyList());

        // Employee missing from export, but updated 5 minutes AFTER export generated
        Instant updatedAtAfterExport = exportGeneratedAt.plus(5, ChronoUnit.MINUTES);
        Map<String, Object> missingRow = new HashMap<>();
        missingRow.put("id", UUID.randomUUID());
        missingRow.put("external_employee_id", "EMP-NEWER");
        missingRow.put("updated_at", Timestamp.from(updatedAtAfterExport));

        when(jdbcTemplate.queryForList(contains("s.external_employee_id IS NULL"), eq(tenantId)))
                .thenReturn(List.of(missingRow));

        ReconciliationResponseDto response = reconciliationService.processReconciliation(tenantId, file, "EXP-1", exportGeneratedAt);

        assertEquals(ReconciliationStatus.COMPLETED, response.getStatus());

        // Verified NOT deactivated
        verify(employeeRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("IT-3.4 & IT-3.6: Operator calls POST /approve - deactivates missing employees; second call is idempotent")
    void shouldApproveRunIdempotently() {
        UUID runId = UUID.randomUUID();
        ReconciliationRun run = ReconciliationRun.builder()
                .id(runId)
                .tenantId(tenantId)
                .status(ReconciliationStatus.NEEDS_APPROVAL)
                .missingCount(1)
                .build();

        when(reconciliationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        ReconciliationCandidate candidate = ReconciliationCandidate.builder()
                .runId(runId)
                .externalEmployeeId("EMP-MISSING-1")
                .candidateType(CandidateType.MISSING)
                .disposition("PENDING")
                .build();
        when(reconciliationCandidateRepository.findByRunIdAndCandidateType(runId, CandidateType.MISSING))
                .thenReturn(List.of(candidate));

        UUID empId = UUID.randomUUID();
        Employee emp = Employee.builder()
                .id(empId)
                .tenantId(tenantId)
                .externalEmployeeId("EMP-MISSING-1")
                .status(EmployeeStatus.ACTIVE)
                .currentVersion(2L)
                .officeCode("BLR-EC")
                .build();
        when(employeeRepository.findByTenantIdAndExternalEmployeeId(tenantId, "EMP-MISSING-1"))
                .thenReturn(Optional.of(emp));
        when(employeeRepository.findById(empId)).thenReturn(Optional.of(emp));

        OperatorDecisionRequestDto req = OperatorDecisionRequestDto.builder()
                .reason("Verified with HR")
                .operatorId(UUID.randomUUID())
                .build();

        // 1. First approval
        OperatorDecisionResponseDto res1 = reconciliationService.approveRun(runId, tenantId, req);

        assertEquals(ReconciliationStatus.COMPLETED, res1.getStatus());
        assertEquals(EmployeeStatus.INACTIVE, emp.getStatus());
        assertEquals("APPLIED", candidate.getDisposition());
        verify(outboxEventRepository).save(any(OutboxEvent.class));

        // 2. Second approval on completed run (idempotent no-op)
        OperatorDecisionResponseDto res2 = reconciliationService.approveRun(runId, tenantId, req);
        assertEquals(ReconciliationStatus.COMPLETED, res2.getStatus());
        assertEquals("Run was already approved and completed.", res2.getMessage());
    }

    @Test
    @DisplayName("IT-3.5: Operator calls POST /reject - marks REJECTED with zero deactivations")
    void shouldRejectRunWithoutDeactivations() {
        UUID runId = UUID.randomUUID();
        ReconciliationRun run = ReconciliationRun.builder()
                .id(runId)
                .tenantId(tenantId)
                .status(ReconciliationStatus.NEEDS_APPROVAL)
                .missingCount(5)
                .build();

        when(reconciliationRunRepository.findById(runId)).thenReturn(Optional.of(run));

        ReconciliationCandidate candidate = ReconciliationCandidate.builder()
                .runId(runId)
                .externalEmployeeId("EMP-MISSING-1")
                .candidateType(CandidateType.MISSING)
                .disposition("PENDING")
                .build();
        when(reconciliationCandidateRepository.findByRunIdAndCandidateType(runId, CandidateType.MISSING))
                .thenReturn(List.of(candidate));

        OperatorDecisionRequestDto req = OperatorDecisionRequestDto.builder()
                .reason("Batch file truncated by network failure")
                .operatorId(UUID.randomUUID())
                .build();

        OperatorDecisionResponseDto res = reconciliationService.rejectRun(runId, tenantId, req);

        assertEquals(ReconciliationStatus.REJECTED, res.getStatus());
        assertEquals(0, res.getMissingCount());
        assertEquals("SKIPPED_BRAKE", candidate.getDisposition());

        // Verified zero employee mutations
        verify(employeeRepository, never()).save(any());
        verify(outboxEventRepository, never()).save(any());
    }
}

