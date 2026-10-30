package com.moveinsync.roster.controller;

import com.moveinsync.roster.domain.entity.OutboxEvent;
import com.moveinsync.roster.service.OutboxPoller;
import com.moveinsync.roster.service.SyncEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OutboxController.class)
@DisplayName("OutboxController Operator DLQ REST Tests (Phase 4)")
class OutboxControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private OutboxPoller outboxPoller;

    @MockBean
    private SyncEventService syncEventService;

    @Test
    @DisplayName("GET /api/v1/outbox/dead-letter returns list of dead letter events")
    void testGetDeadLetterEvents() throws Exception {
        UUID tenantId = UUID.randomUUID();
        when(syncEventService.resolveTenantId(any())).thenReturn(tenantId);

        OutboxEvent event = OutboxEvent.builder()
                .id(UUID.randomUUID())
                .aggregateType("EMPLOYEE")
                .aggregateId(UUID.randomUUID())
                .eventType("EMPLOYEE_DEACTIVATED")
                .tenantId(tenantId)
                .payload(Map.of("emp", "123"))
                .status("DEAD_LETTER")
                .retryCount(10)
                .lastError("Network 503")
                .createdAt(Instant.now())
                .build();

        when(outboxPoller.getDeadLetterEvents(tenantId)).thenReturn(List.of(event));

        mockMvc.perform(get("/api/v1/outbox/dead-letter")
                        .header("X-Tenant-ID", tenantId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("DEAD_LETTER"))
                .andExpect(jsonPath("$[0].event_type").value("EMPLOYEE_DEACTIVATED"))
                .andExpect(jsonPath("$[0].retry_count").value(10));
    }

    @Test
    @DisplayName("POST /api/v1/outbox/{eventId}/replay resets dead letter event to PENDING")
    void testReplayDeadLetterEvent() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(syncEventService.resolveTenantId(any())).thenReturn(tenantId);

        OutboxEvent replayed = OutboxEvent.builder()
                .id(eventId)
                .aggregateType("EMPLOYEE")
                .aggregateId(UUID.randomUUID())
                .eventType("EMPLOYEE_DEACTIVATED")
                .tenantId(tenantId)
                .payload(Map.of())
                .status("PENDING")
                .retryCount(0)
                .createdAt(Instant.now())
                .build();

        when(outboxPoller.replayDeadLetterEvent(eq(eventId), eq(tenantId))).thenReturn(replayed);

        mockMvc.perform(post("/api/v1/outbox/" + eventId + "/replay")
                        .header("X-Tenant-ID", tenantId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.retry_count").value(0));
    }

    @Test
    @DisplayName("POST /api/v1/outbox/{eventId}/replay returns 404 when event not found")
    void testReplayEventNotFound() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        when(syncEventService.resolveTenantId(any())).thenReturn(tenantId);

        when(outboxPoller.replayDeadLetterEvent(eq(eventId), eq(tenantId)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Outbox event not found"));

        mockMvc.perform(post("/api/v1/outbox/" + eventId + "/replay")
                        .header("X-Tenant-ID", tenantId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }
}

