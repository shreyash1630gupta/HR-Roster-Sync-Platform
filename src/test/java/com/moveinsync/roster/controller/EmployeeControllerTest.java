package com.moveinsync.roster.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.dto.EmployeeDetailsDto;
import com.moveinsync.roster.domain.dto.TransportFieldUpdateRequest;
import com.moveinsync.roster.domain.enums.EmployeeStatus;
import com.moveinsync.roster.service.EmployeeService;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EmployeeController.class)
@DisplayName("EmployeeController Integration Tests")
class EmployeeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EmployeeService employeeService;

    @MockBean
    private SyncEventService syncEventService;

    @Test
    @DisplayName("GET /api/v1/employees/{id} returns segregated HR and Transport fields")
    void shouldReturnSegregatedEmployeeDetails() throws Exception {
        UUID empId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        when(syncEventService.resolveTenantId(any())).thenReturn(tenantId);

        EmployeeDetailsDto dto = EmployeeDetailsDto.builder()
                .id(empId)
                .tenantId(tenantId)
                .externalEmployeeId("EMP-1001")
                .status(EmployeeStatus.ACTIVE)
                .currentVersion(5L)
                .hrOwnedFields(Map.of(
                        "name", "Rahul Sharma",
                        "email", "rahul@example.com",
                        "office_code", "BLR-EC"
                ))
                .transportOwnedFields(Map.of(
                        "pickup_point", "Forum Mall",
                        "special_assistance_needs", "None"
                ))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(employeeService.getEmployeeById(eq(tenantId), eq(empId))).thenReturn(dto);

        mockMvc.perform(get("/api/v1/employees/" + empId)
                        .header("X-Tenant-ID", tenantId.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.external_employee_id").value("EMP-1001"))
                .andExpect(jsonPath("$.hr_owned_fields.name").value("Rahul Sharma"))
                .andExpect(jsonPath("$.hr_owned_fields.office_code").value("BLR-EC"))
                .andExpect(jsonPath("$.transport_owned_fields.pickup_point").value("Forum Mall"));
    }

    @Test
    @DisplayName("GET /api/v1/employees/{id} returns 404 when employee does not exist")
    void shouldReturn404WhenEmployeeNotFound() throws Exception {
        UUID empId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        when(syncEventService.resolveTenantId(any())).thenReturn(tenantId);
        when(employeeService.getEmployeeById(eq(tenantId), eq(empId)))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

        mockMvc.perform(get("/api/v1/employees/" + empId)
                        .header("X-Tenant-ID", tenantId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("PUT /api/v1/employees/{id}/transport-fields updates transport fields")
    void shouldUpdateTransportFields() throws Exception {
        UUID empId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        when(syncEventService.resolveTenantId(any())).thenReturn(tenantId);

        TransportFieldUpdateRequest request = TransportFieldUpdateRequest.builder()
                .pickupPoint("Silk Board")
                .specialAssistanceNeeds("Wheelchair")
                .build();

        EmployeeDetailsDto updatedDto = EmployeeDetailsDto.builder()
                .id(empId)
                .tenantId(tenantId)
                .externalEmployeeId("EMP-1001")
                .status(EmployeeStatus.ACTIVE)
                .currentVersion(5L)
                .hrOwnedFields(Map.of("name", "Rahul Sharma"))
                .transportOwnedFields(Map.of(
                        "pickup_point", "Silk Board",
                        "special_assistance_needs", "Wheelchair"
                ))
                .build();

        when(employeeService.updateTransportFields(eq(tenantId), eq(empId), any(TransportFieldUpdateRequest.class)))
                .thenReturn(updatedDto);

        mockMvc.perform(put("/api/v1/employees/" + empId + "/transport-fields")
                        .header("X-Tenant-ID", tenantId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transport_owned_fields.pickup_point").value("Silk Board"))
                .andExpect(jsonPath("$.transport_owned_fields.special_assistance_needs").value("Wheelchair"));
    }
}

