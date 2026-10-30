package com.moveinsync.roster.validation;

import com.moveinsync.roster.domain.dto.EmployeeDataDto;
import com.moveinsync.roster.domain.dto.SyncEventRequest;
import com.moveinsync.roster.domain.enums.EventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("UpdateEventValidator Unit Tests (UT-1.6)")
class UpdateEventValidatorTest {

    private UpdateEventValidator validator;

    @BeforeEach
    void setUp() {
        validator = new UpdateEventValidator();
    }

    @Test
    @DisplayName("UT-1.6: Rejects partial update missing required HR snapshot fields")
    void shouldRejectPartialUpdateMissingSnapshotFields() {
        // Omits name and office_code
        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.UPDATE)
                .tenantId("tenant-infosys")
                .externalEmployeeId("EMP-10042")
                .sourceVersion(2L)
                .timestamp(Instant.now())
                .data(EmployeeDataDto.builder()
                        .email("updated.email@infosys.com")
                        .build())
                .build();

        ValidationResult result = validator.validate(request);

        assertFalse(result.isValid(), "Partial updates in V1 must be rejected; full snapshot required");
        assertTrue(result.getErrors().contains("Name is required"));
        assertTrue(result.getErrors().contains("Office code is required"));
    }

    @Test
    @DisplayName("UT-1.6b: Accepts full snapshot update")
    void shouldAcceptFullSnapshotUpdate() {
        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.UPDATE)
                .tenantId("tenant-infosys")
                .externalEmployeeId("EMP-10042")
                .sourceVersion(2L)
                .timestamp(Instant.now())
                .data(EmployeeDataDto.builder()
                        .name("Rahul Sharma")
                        .email("rahul.sharma@infosys.com")
                        .officeCode("BLR-EC")
                        .shift("NIGHT")
                        .build())
                .build();

        ValidationResult result = validator.validate(request);

        assertTrue(result.isValid(), "Full snapshot update must be accepted");
        assertTrue(result.getErrors().isEmpty());
    }
}

