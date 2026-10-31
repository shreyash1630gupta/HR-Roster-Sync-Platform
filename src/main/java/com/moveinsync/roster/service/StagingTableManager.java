package com.moveinsync.roster.service;

import com.moveinsync.roster.domain.dto.EmployeeDataDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class StagingTableManager {

    private static final int BATCH_CHUNK_SIZE = 1000;
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,6}$");

    private final JdbcTemplate jdbcTemplate;
    private final SyncEngine syncEngine;

    public static class StagingLoadResult {
        public String fileChecksum;
        public int totalRows;
        public int validRows;
        public int invalidRows;
        public String tableName;
    }

    public static class StagingRow {
        public UUID tenantId;
        public String externalEmployeeId;
        public Long sourceVersion;
        public String name;
        public String email;
        public String phone;
        public String managerId;
        public String officeCode;
        public String shift;
        public String department;
        public String designation;
        public String recordHash;
        public String validationStatus;
        public String errorMessage;
    }

    public String getTableName(UUID runId) {
        String cleanId = runId.toString().replace("-", "_");
        return "recon_staging_" + cleanId;
    }

    public void createStagingTable(String tableName) {
        String sql = String.format("""
            CREATE TABLE IF NOT EXISTS %s (
                tenant_id UUID NOT NULL,
                external_employee_id VARCHAR(64),
                source_version BIGINT,
                name VARCHAR(200),
                email VARCHAR(255),
                phone VARCHAR(32),
                manager_id VARCHAR(64),
                office_code VARCHAR(64),
                shift VARCHAR(64),
                department VARCHAR(100),
                designation VARCHAR(100),
                record_hash CHAR(64),
                validation_status VARCHAR(32) DEFAULT 'VALID',
                error_message TEXT
            );
            CREATE INDEX IF NOT EXISTS idx_%s_ext ON %s (tenant_id, external_employee_id);
            """, tableName, tableName, tableName);

        try {
            jdbcTemplate.execute(sql);
            log.info("Created staging table: {}", tableName);
        } catch (Exception e) {
            log.error("Failed to create staging table {}: {}", tableName, e.getMessage());
            throw e;
        }
    }

    public void dropStagingTable(String tableName) {
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
            log.info("Dropped staging table: {}", tableName);
        } catch (Exception e) {
            log.warn("Failed to drop staging table {}: {}", tableName, e.getMessage());
        }
    }

    /**
     * Streams CSV file with chunked batch loading (1,000 rows at a time) to guarantee bounded memory (< 5 MB heap).
     * Simultaneously computes the SHA-256 checksum of the stream.
     */
    public StagingLoadResult streamCsvIntoStaging(InputStream rawInputStream, UUID tenantId, String tableName) throws Exception {
        MessageDigest shaDigest = MessageDigest.getInstance("SHA-256");
        DigestInputStream digestInputStream = new DigestInputStream(rawInputStream, shaDigest);
        BufferedReader reader = new BufferedReader(new InputStreamReader(digestInputStream, StandardCharsets.UTF_8));

        createStagingTable(tableName);

        String headerLine = reader.readLine();
        if (headerLine == null || headerLine.isBlank()) {
            // Empty file
            byte[] hashBytes = shaDigest.digest();
            StagingLoadResult res = new StagingLoadResult();
            res.fileChecksum = bytesToHex(hashBytes);
            res.totalRows = 0;
            res.validRows = 0;
            res.invalidRows = 0;
            res.tableName = tableName;
            return res;
        }

        Map<String, Integer> colIndex = parseHeader(headerLine);
        List<StagingRow> chunk = new ArrayList<>(BATCH_CHUNK_SIZE);
        int totalRows = 0;
        int validRows = 0;
        int invalidRows = 0;

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.trim().isEmpty()) continue;
            totalRows++;

            StagingRow row = parseAndValidateLine(line, colIndex, tenantId);
            if ("VALID".equals(row.validationStatus)) {
                validRows++;
            } else {
                invalidRows++;
            }
            chunk.add(row);

            if (chunk.size() >= BATCH_CHUNK_SIZE) {
                flushChunk(chunk, tableName);
                chunk.clear();
            }
        }

        if (!chunk.isEmpty()) {
            flushChunk(chunk, tableName);
            chunk.clear();
        }

        byte[] hashBytes = shaDigest.digest();
        StagingLoadResult result = new StagingLoadResult();
        result.fileChecksum = bytesToHex(hashBytes);
        result.totalRows = totalRows;
        result.validRows = validRows;
        result.invalidRows = invalidRows;
        result.tableName = tableName;

        log.info("Finished streaming CSV into {}: total={}, valid={}, invalid={}, checksum={}",
                tableName, totalRows, validRows, invalidRows, result.fileChecksum);

        return result;
    }

    private void flushChunk(List<StagingRow> rows, String tableName) {
        String insertSql = String.format("""
            INSERT INTO %s (tenant_id, external_employee_id, source_version, name, email, phone,
                           manager_id, office_code, shift, department, designation, record_hash,
                           validation_status, error_message)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, tableName);

        try {
            jdbcTemplate.batchUpdate(insertSql, rows, rows.size(), (ps, row) -> {
                ps.setObject(1, row.tenantId);
                ps.setString(2, row.externalEmployeeId);
                if (row.sourceVersion != null) ps.setLong(3, row.sourceVersion); else ps.setNull(3, java.sql.Types.BIGINT);
                ps.setString(4, row.name);
                ps.setString(5, row.email);
                ps.setString(6, row.phone);
                ps.setString(7, row.managerId);
                ps.setString(8, row.officeCode);
                ps.setString(9, row.shift);
                ps.setString(10, row.department);
                ps.setString(11, row.designation);
                ps.setString(12, row.recordHash);
                ps.setString(13, row.validationStatus);
                ps.setString(14, row.errorMessage);
            });
        } catch (Exception e) {
            log.error("Batch insert into staging {} failed: {}", tableName, e.getMessage());
            throw e;
        }
    }

    private Map<String, Integer> parseHeader(String headerLine) {
        if (headerLine != null && headerLine.startsWith("\uFEFF")) {
            headerLine = headerLine.substring(1);
        }
        String[] headers = headerLine.split(",");
        Map<String, Integer> map = new HashMap<>();
        for (int i = 0; i < headers.length; i++) {
            map.put(headers[i].trim().toLowerCase(), i);
        }
        return map;
    }

    private StagingRow parseAndValidateLine(String line, Map<String, Integer> colIndex, UUID tenantId) {
        String[] parts = line.split(",", -1);
        StagingRow row = new StagingRow();
        row.tenantId = tenantId;
        row.externalEmployeeId = getVal(parts, colIndex, "external_employee_id");
        if (row.externalEmployeeId == null || row.externalEmployeeId.isBlank()) {
            row.externalEmployeeId = getVal(parts, colIndex, "employee_id");
        }
        if (row.externalEmployeeId == null || row.externalEmployeeId.isBlank()) {
            row.externalEmployeeId = getVal(parts, colIndex, "id");
        }

        String verStr = getVal(parts, colIndex, "source_version");
        if (verStr == null || verStr.isBlank()) {
            verStr = getVal(parts, colIndex, "version");
        }
        if (verStr != null && !verStr.isBlank()) {
            try {
                row.sourceVersion = Long.parseLong(verStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        row.name = getVal(parts, colIndex, "name");
        row.email = getVal(parts, colIndex, "email");
        row.phone = getVal(parts, colIndex, "phone");
        row.managerId = getVal(parts, colIndex, "manager_id");
        row.officeCode = getVal(parts, colIndex, "office_code");
        row.shift = getVal(parts, colIndex, "shift");
        row.department = getVal(parts, colIndex, "department");
        row.designation = getVal(parts, colIndex, "designation");

        // Validate mandatory fields
        List<String> errors = new ArrayList<>();
        if (row.externalEmployeeId == null || row.externalEmployeeId.isBlank()) {
            errors.add("Missing external_employee_id");
        }
        if (row.name == null || row.name.isBlank()) {
            errors.add("Missing name");
        }
        if (row.email == null || row.email.isBlank()) {
            errors.add("Missing email");
        } else if (!EMAIL_PATTERN.matcher(row.email.trim()).matches()) {
            errors.add("Malformed email");
        }
        if (row.officeCode == null || row.officeCode.isBlank()) {
            errors.add("Missing office_code");
        }

        if (!errors.isEmpty()) {
            row.validationStatus = "INVALID";
            row.errorMessage = String.join("; ", errors);
            row.recordHash = "0000000000000000000000000000000000000000000000000000000000000000";
        } else {
            row.validationStatus = "VALID";
            EmployeeDataDto dto = EmployeeDataDto.builder()
                    .name(row.name)
                    .email(row.email)
                    .phone(row.phone)
                    .managerId(row.managerId)
                    .officeCode(row.officeCode)
                    .shift(row.shift)
                    .department(row.department)
                    .designation(row.designation)
                    .build();
            row.recordHash = syncEngine.computePayloadHash(dto);
        }

        return row;
    }

    private String getVal(String[] parts, Map<String, Integer> map, String key) {
        Integer idx = map.get(key);
        if (idx != null && idx < parts.length) {
            String val = parts[idx].trim();
            return val.isEmpty() ? null : val;
        }
        return null;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }
}

