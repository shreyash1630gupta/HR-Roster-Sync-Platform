package com.moveinsync.roster.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantAdvisoryLockService Unit Tests (IT-3.11)")
class TenantAdvisoryLockServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private TenantAdvisoryLockService lockService;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        lockService = new TenantAdvisoryLockService(jdbcTemplate);
        tenantId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Acquires lock successfully when no competing reconciliation is active")
    void shouldAcquireLockSuccessfully() {
        when(jdbcTemplate.queryForObject(eq("SELECT pg_try_advisory_lock(?)"), eq(Boolean.class), anyInt()))
                .thenReturn(Boolean.TRUE);

        boolean acquired = lockService.tryAcquire(tenantId);
        assertTrue(acquired, "Lock should be acquired");
    }

    @Test
    @DisplayName("Rejects second concurrent lock acquisition for the same tenant")
    void shouldRejectSecondConcurrentLockForSameTenant() {
        when(jdbcTemplate.queryForObject(eq("SELECT pg_try_advisory_lock(?)"), eq(Boolean.class), anyInt()))
                .thenReturn(Boolean.TRUE);

        boolean first = lockService.tryAcquire(tenantId);
        assertTrue(first, "First attempt must succeed");

        // Second attempt in same process rejected immediately by in-memory activeLocks guard
        boolean second = lockService.tryAcquire(tenantId);
        assertFalse(second, "Second concurrent attempt must be rejected");
    }

    @Test
    @DisplayName("Allows lock acquisition after previous lock is released")
    void shouldAllowLockAfterRelease() {
        when(jdbcTemplate.queryForObject(eq("SELECT pg_try_advisory_lock(?)"), eq(Boolean.class), anyInt()))
                .thenReturn(Boolean.TRUE);

        assertTrue(lockService.tryAcquire(tenantId));
        lockService.release(tenantId);

        assertTrue(lockService.tryAcquire(tenantId), "Should be able to re-acquire after release");
    }
}

