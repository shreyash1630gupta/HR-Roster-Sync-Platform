package com.moveinsync.roster.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StagingTableManager Unit Tests (IT-3.12, BT-3.14)")
class StagingTableManagerTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SyncEngine syncEngine;
    private StagingTableManager stagingTableManager;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        syncEngine = new SyncEngine(null, null, null, new FieldOwnershipService());
        stagingTableManager = new StagingTableManager(jdbcTemplate, syncEngine);
    }

    @Test
    @DisplayName("Streams CSV and segregates valid from invalid rows without aborting")
    void shouldStreamCsvAndSegregateValidFromInvalidRows() throws Exception {
        String csvContent = """
                external_employee_id,source_version,name,email,phone,manager_id,office_code,shift,department,designation
                EMP-1,1,Alice,alice@example.com,1234567890,M-1,BLR-EC,GENERAL,Eng,Dev
                EMP-2,2,Bob,bad-email-format,9876543210,M-1,BLR-EC,GENERAL,Eng,QA
                EMP-3,1,Charlie,charlie@example.com,1122334455,M-2,BLR-EC,GENERAL,Eng,Lead
                """;

        ByteArrayInputStream is = new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8));
        String tableName = stagingTableManager.getTableName(UUID.randomUUID());

        StagingTableManager.StagingLoadResult result = stagingTableManager.streamCsvIntoStaging(is, tenantId, tableName);

        assertNotNull(result);
        assertEquals(3, result.totalRows, "Total parsed rows must be 3");
        assertEquals(2, result.validRows, "2 valid rows expected");
        assertEquals(1, result.invalidRows, "1 invalid row (bad email) expected");
        assertNotNull(result.fileChecksum);
        assertEquals(64, result.fileChecksum.length(), "SHA-256 checksum must be 64 characters");

        // Verify create table and batchUpdate were called
        verify(jdbcTemplate, atLeastOnce()).execute(anyString());
        verify(jdbcTemplate, atLeastOnce()).batchUpdate(anyString(), anyList(), anyInt(), any());
    }

    @Test
    @DisplayName("Handles empty CSV file cleanly")
    void shouldHandleEmptyCsv() throws Exception {
        ByteArrayInputStream is = new ByteArrayInputStream(new byte[0]);
        String tableName = stagingTableManager.getTableName(UUID.randomUUID());

        StagingTableManager.StagingLoadResult result = stagingTableManager.streamCsvIntoStaging(is, tenantId, tableName);

        assertNotNull(result);
        assertEquals(0, result.totalRows);
        assertEquals(0, result.validRows);
        assertEquals(0, result.invalidRows);
        assertNotNull(result.fileChecksum);
    }
}

