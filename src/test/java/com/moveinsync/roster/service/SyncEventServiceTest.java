package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.dto.BatchSyncResponse;
import com.moveinsync.roster.domain.dto.EmployeeDataDto;
import com.moveinsync.roster.domain.dto.EventResultDto;
import com.moveinsync.roster.domain.dto.SyncEventRequest;
import com.moveinsync.roster.domain.entity.Tenant;
import com.moveinsync.roster.domain.enums.EventType;
import com.moveinsync.roster.domain.enums.ProcessingStatus;
import com.moveinsync.roster.repository.TenantRepository;
import com.moveinsync.roster.validation.EventValidator;
import com.moveinsync.roster.validation.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SyncEventService Batch Ingestion Tests (IT-1.8)")
class SyncEventServiceTest {

    @Mock
    private EventValidator eventValidator;

    @Mock
    private SyncEngine syncEngine;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private com.moveinsync.roster.metrics.RosterMetrics rosterMetrics;

    @InjectMocks
    private SyncEventService syncEventService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
    }

    @Test
    @DisplayName("IT-1.8: One bad record in batch does not fail the other 9 records")
    void batchWithOneInvalidShouldAcceptRemaining() {
        List<SyncEventRequest> events = new ArrayList<>();
        when(tenantRepository.findByCode(any())).thenReturn(Optional.of(Tenant.builder().id(tenantId).build()));

        // Create 10 events: index 1 is invalid, others are valid
        for (int i = 0; i < 10; i++) {
            SyncEventRequest req = SyncEventRequest.builder()
                    .eventId("evt-" + i)
                    .eventType(EventType.CREATE)
                    .tenantId("tenant-infosys")
                    .externalEmployeeId("EMP-" + i)
                    .sourceVersion(1L)
                    .timestamp(Instant.now())
                    .data(EmployeeDataDto.builder()
                            .name("Employee " + i)
                            .email("emp" + i + "@example.com")
                            .officeCode("BLR-EC")
                            .build())
                    .build();
            events.add(req);

            if (i == 1) {
                // Invalid event (bad email)
                when(eventValidator.validate(req)).thenReturn(ValidationResult.fail("Malformed email"));
            } else {
                when(eventValidator.validate(req)).thenReturn(ValidationResult.ok());
            }
        }

        when(syncEngine.processEventInTransaction(anyInt(), any(SyncEventRequest.class), any(UUID.class)))
                .thenAnswer(invocation -> {
                    int idx = invocation.getArgument(0);
                    SyncEventRequest request = invocation.getArgument(1);
                    return EventResultDto.builder()
                            .index(idx)
                            .eventId(request.getEventId())
                            .externalEmployeeId(request.getExternalEmployeeId())
                            .status(ProcessingStatus.ACCEPTED)
                            .action("CREATED")
                            .build();
                });

        BatchSyncResponse response = syncEventService.processBatch(events);

        assertNotNull(response);
        assertEquals(10, response.getTotal(), "Total records in batch must be 10");
        assertEquals(9, response.getAccepted(), "9 records should be accepted");
        assertEquals(1, response.getRejected(), "1 record should be rejected");
        assertEquals(0, response.getSkipped());
        assertEquals(0, response.getConflicts());

        assertEquals(ProcessingStatus.ACCEPTED, response.getResults().get(0).getStatus());
        assertEquals(ProcessingStatus.REJECTED, response.getResults().get(1).getStatus());
        assertTrue(response.getResults().get(1).getErrors().contains("Malformed email"));
        assertEquals(ProcessingStatus.ACCEPTED, response.getResults().get(2).getStatus());

        // Verify syncEngine was called 9 times (only for valid events)
        verify(syncEngine, times(9)).processEventInTransaction(anyInt(), any(), any());
    }
}
