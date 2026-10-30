package com.moveinsync.roster.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantAdvisoryLockService {

    private final JdbcTemplate jdbcTemplate;
    private final Set<UUID> activeLocks = ConcurrentHashMap.newKeySet();

    /**
     * Attempts to acquire tenant-level advisory lock via PostgreSQL pg_try_advisory_lock
     * combined with in-memory lock tracking.
     */
    public boolean tryAcquire(UUID tenantId) {
        if (!activeLocks.add(tenantId)) {
            log.warn("Reconciliation lock already held in-process for tenantId={}", tenantId);
            return false;
        }

        try {
            // Hash tenant UUID to 32-bit integer for PostgreSQL advisory lock
            int lockKey = Math.abs(tenantId.hashCode());
            Boolean acquired = jdbcTemplate.queryForObject(
                    "SELECT pg_try_advisory_lock(?)", Boolean.class, lockKey
            );

            if (Boolean.TRUE.equals(acquired)) {
                log.info("Acquired PostgreSQL advisory lock for tenantId={}, key={}", tenantId, lockKey);
                return true;
            } else {
                activeLocks.remove(tenantId);
                log.warn("PostgreSQL advisory lock rejected (already held) for tenantId={}, key={}", tenantId, lockKey);
                return false;
            }
        } catch (Exception e) {
            log.warn("PostgreSQL advisory lock query skipped or failed ({}), relying on in-memory guard", e.getMessage());
            // If PostgreSQL is running H2/test or query fails, in-memory activeLocks guard remains active
            return true;
        }
    }

    /**
     * Releases tenant-level advisory lock.
     */
    public void release(UUID tenantId) {
        activeLocks.remove(tenantId);
        try {
            int lockKey = Math.abs(tenantId.hashCode());
            jdbcTemplate.queryForObject("SELECT pg_advisory_unlock(?)", Boolean.class, lockKey);
            log.info("Released PostgreSQL advisory lock for tenantId={}, key={}", tenantId, lockKey);
        } catch (Exception e) {
            log.debug("PostgreSQL advisory unlock failed or skipped: {}", e.getMessage());
        }
    }
}

