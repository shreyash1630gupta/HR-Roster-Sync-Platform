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

@DisplayName("CreateEventValidator Unit Tests (UT-1.1 to UT-1.3)")
class CreateEventValidatorTest {

    private CreateEventValidator validator;

    @BeforeEach
    void setUp() {
        validator = new CreateEventValidator();
    }

    @Test
    @DisplayName("UT-1.1: Rejects payload with malformed email (RFC 5322)")
    void shouldRejectMalformedEmail() {
        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.CREATE)
                .tenantId("tenant-infosys")
                .externalEmployeeId("EMP-1001")
                .sourceVersion(1L)
                .timestamp(Instant.now())
                .data(EmployeeDataDto.builder()
                        .name("Rahul Sharma")
                        .email("invalid-email-address")
                        .officeCode("BLR-EC")
                        .build())
                .build();

        ValidationResult result = validator.validate(request);

        assertFalse(result.isValid(), "Validation should fail for malformed email");
        assertTrue(result.getErrors().contains("Malformed email"));
        // Ensure no PII in errors
        for (String error : result.getErrors()) {
            assertFalse(error.contains("Rahul"), "PII (name) must not leak into error messages");
            assertFalse(error.contains("invalid-email-address"), "PII (email) must not leak into error messages");
        }
    }

    @Test
    @DisplayName("UT-1.1b: Rejects missing mandatory fields in CREATE (name, email, office_code)")
    void shouldRejectMissingMandatoryFields() {
        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.CREATE)
                .tenantId("tenant-infosys")
                .externalEmployeeId("EMP-1001")
                .sourceVersion(1L)
                .timestamp(Instant.now())
                .data(EmployeeDataDto.builder().build())
                .build();

        ValidationResult result = validator.validate(request);

        assertFalse(result.isValid());
        assertTrue(result.getErrors().contains("Name is required"));
        assertTrue(result.getErrors().contains("Email is required"));
        assertTrue(result.getErrors().contains("Office code is required"));
    }

    @Test
    @DisplayName("UT-1.3: Accepts valid CREATE event with full snapshot")
    void shouldAcceptValidCreateEvent() {
        SyncEventRequest request = SyncEventRequest.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(EventType.CREATE)
                .tenantId("tenant-infosys")
                .externalEmployeeId("EMP-10042")
                .sourceVersion(1L)
                .timestamp(Instant.now())
                .data(EmployeeDataDto.builder()
                        .name("Rahul Sharma")
                        .email("rahul.sharma@infosys.com")
                        .phone("+919876543210")
                        .managerId("EMP-5001")
                        .officeCode("BLR-EC")
                        .shift("GENERAL")
                        .department("Engineering")
                        .designation("Software Engineer")
                        .build())
                .build();

        ValidationResult result = validator.validate(request);

        assertTrue(result.isValid(), "Validation should pass for complete, valid CREATE event");
        assertTrue(result.getErrors().isEmpty());
    }
}

