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

@DisplayName("DeactivateEventValidator Unit Tests (UT-1.4 to UT-1.5)")
class DeactivateEventValidatorTest {

    private DeactivateEventValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DeactivateEventValidator();
    }

    @Test
    @DisplayName("UT-1.4: Accepts exit event with envelope fields only (no data payload)")
    void shouldAcceptDeactivateWithoutDataPayload() {
        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.DEACTIVATE)
                .tenantId("tenant-infosys")
                .externalEmployeeId("EMP-10042")
                .sourceVersion(9L)
                .timestamp(Instant.now())
                .data(null) // envelope only
                .build();

        ValidationResult result = validator.validate(request);

        assertTrue(result.isValid(), "DEACTIVATE with envelope only must be accepted");
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    @DisplayName("UT-1.5: Rejects exit event that contains a data body")
    void shouldRejectDeactivateWithDataPayload() {
        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.DEACTIVATE)
                .tenantId("tenant-infosys")
                .externalEmployeeId("EMP-10042")
                .sourceVersion(9L)
                .timestamp(Instant.now())
                .data(EmployeeDataDto.builder()
                        .name("Should Not Be Here")
                        .email("test@example.com")
                        .build())
                .build();

        ValidationResult result = validator.validate(request);

        assertFalse(result.isValid(), "DEACTIVATE containing data body must be rejected");
        assertTrue(result.getErrors().contains("DEACTIVATE must not carry a data payload"));
    }
}

