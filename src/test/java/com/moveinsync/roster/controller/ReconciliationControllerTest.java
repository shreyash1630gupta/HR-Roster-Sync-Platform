package com.moveinsync.roster.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.dto.OperatorDecisionRequestDto;
import com.moveinsync.roster.domain.dto.OperatorDecisionResponseDto;
import com.moveinsync.roster.domain.dto.ReconciliationDetailsDto;
import com.moveinsync.roster.domain.dto.ReconciliationResponseDto;
import com.moveinsync.roster.domain.enums.ReconciliationStatus;
import com.moveinsync.roster.service.ReconciliationService;
import com.moveinsync.roster.service.SyncEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReconciliationController.class)
@DisplayName("ReconciliationController Integration Tests (Phase 3)")
class ReconciliationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ReconciliationService reconciliationService;

    @MockBean
    private SyncEventService syncEventService;

    @Test
    @DisplayName("POST /api/v1/sync/reconcile uploads multipart CSV and returns 200 OK")
    void shouldUploadReconciliationCsv() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        when(syncEventService.resolveTenantId(any())).thenReturn(tenantId);

        ReconciliationResponseDto mockRes = ReconciliationResponseDto.builder()
                .runId(runId)
                .status(ReconciliationStatus.COMPLETED)
                .message("Reconciliation completed successfully.")
                .build();

        when(reconciliationService.processReconciliation(eq(tenantId), any(), any(), any()))
                .thenReturn(mockRes);

        MockMultipartFile file = new MockMultipartFile(
                "file", "export.csv", "text/csv", "col1,col2\nval1,val2".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/sync/reconcile")
                        .file(file)
                        .param("generated_at", "2026-10-30T10:00:00Z")
                        .param("export_id", "EXP-001")
                        .header("X-Tenant-ID", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    @DisplayName("GET /api/v1/reconciliation/{runId} returns run stats and details")
    void shouldReturnReconciliationDetails() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        when(syncEventService.resolveTenantId(any())).thenReturn(tenantId);

        ReconciliationDetailsDto details = ReconciliationDetailsDto.builder()
                .runId(runId)
                .tenantId(tenantId)
                .status(ReconciliationStatus.NEEDS_APPROVAL)
                .exportId("EXP-001")
                .stats(Map.of("missing_count", 40, "total_in_export", 60))
                .safetyBrakeTriggered(true)
                .build();

        when(reconciliationService.getRunDetails(eq(runId), eq(tenantId))).thenReturn(details);

        mockMvc.perform(get("/api/v1/reconciliation/" + runId)
                        .header("X-Tenant-ID", tenantId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.run_id").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("NEEDS_APPROVAL"))
                .andExpect(jsonPath("$.safety_brake_triggered").value(true));
    }

    @Test
    @DisplayName("POST /api/v1/reconciliation/{runId}/approve executes operator approval")
    void shouldApproveReconciliation() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        when(syncEventService.resolveTenantId(any())).thenReturn(tenantId);

        OperatorDecisionRequestDto req = OperatorDecisionRequestDto.builder()
                .reason("Verified with HR director")
                .operatorId(UUID.randomUUID())
                .build();

        OperatorDecisionResponseDto res = OperatorDecisionResponseDto.builder()
                .runId(runId)
                .status(ReconciliationStatus.COMPLETED)
                .missingCount(40)
                .message("Deactivations approved and applied.")
                .decidedAt(Instant.now())
                .build();

        when(reconciliationService.approveRun(eq(runId), eq(tenantId), any())).thenReturn(res);

        mockMvc.perform(post("/api/v1/reconciliation/" + runId + "/approve")
                        .header("X-Tenant-ID", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.missing_count").value(40));
    }

    @Test
    @DisplayName("POST /api/v1/reconciliation/{runId}/reject executes operator rejection")
    void shouldRejectReconciliation() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        when(syncEventService.resolveTenantId(any())).thenReturn(tenantId);

        OperatorDecisionRequestDto req = OperatorDecisionRequestDto.builder()
                .reason("Export file was truncated by network error")
                .build();

        OperatorDecisionResponseDto res = OperatorDecisionResponseDto.builder()
                .runId(runId)
                .status(ReconciliationStatus.REJECTED)
                .missingCount(0)
                .message("Reconciliation run rejected. Zero employee records deactivated.")
                .decidedAt(Instant.now())
                .build();

        when(reconciliationService.rejectRun(eq(runId), eq(tenantId), any())).thenReturn(res);

        mockMvc.perform(post("/api/v1/reconciliation/" + runId + "/reject")
                        .header("X-Tenant-ID", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.missing_count").value(0));
    }
}

