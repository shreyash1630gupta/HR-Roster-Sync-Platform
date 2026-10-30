package com.moveinsync.roster.controller;

import com.moveinsync.roster.domain.dto.BatchSyncRequest;
import com.moveinsync.roster.domain.dto.BatchSyncResponse;
import com.moveinsync.roster.domain.dto.SyncEventRequest;
import com.moveinsync.roster.service.SyncEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SyncEventController.class)
@DisplayName("SyncEventController Integration Tests (IT-1.9)")
class SyncEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SyncEventService syncEventService;

    @Test
    @DisplayName("IT-1.9: Rejects batch exceeding 1,000 events ceiling with HTTP 413 Payload Too Large")
    void shouldRejectBatchExceedingCeiling() throws Exception {
        // Create 1001 empty events
        List<SyncEventRequest> events = new ArrayList<>(1001);
        for (int i = 0; i < 1001; i++) {
            events.add(new SyncEventRequest());
        }

        // Build a JSON payload with 1001 items
        StringBuilder json = new StringBuilder("{\"events\":[");
        for (int i = 0; i < 1001; i++) {
            json.append("{\"event_id\":\"evt-").append(i).append("\"}");
            if (i < 1000) json.append(",");
        }
        json.append("]}");

        mockMvc.perform(post("/api/v1/sync/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.toString()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").value("Batch size exceeds maximum limit of 1000 events"));
    }

    @Test
    @DisplayName("Accepts normal batch within ceiling and returns 200 OK")
    void shouldAcceptNormalBatch() throws Exception {
        BatchSyncResponse mockResponse = BatchSyncResponse.builder()
                .batchId("batch-123")
                .total(1)
                .accepted(1)
                .results(Collections.emptyList())
                .build();

        when(syncEventService.processBatch(any())).thenReturn(mockResponse);

        String json = "{\"events\":[{\"event_id\":\"evt-1\"}]}";

        mockMvc.perform(post("/api/v1/sync/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batch_id").value("batch-123"))
                .andExpect(jsonPath("$.total").value(1));
    }
}

