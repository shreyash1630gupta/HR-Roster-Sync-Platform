package com.moveinsync.roster.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.moveinsync.roster.domain.entity.Office;
import com.moveinsync.roster.repository.OfficeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Service that mediates office lookups through a tenant-scoped Caffeine in-memory cache.
 * Provides explicit eviction hooks for office create/update/deactivate mutations.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfficeCacheService {

    private final OfficeRepository officeRepository;
    private final Cache<TenantOfficeKey, Optional<Office>> officeValidationCache;

    /**
     * Looks up an office by tenantId and code.
     * Hits Caffeine cache on subsequent invocations, avoiding database queries.
     */
    public Optional<Office> getOffice(UUID tenantId, String code) {
        if (tenantId == null || code == null || code.isBlank()) {
            return Optional.empty();
        }

        TenantOfficeKey key = new TenantOfficeKey(tenantId, code.trim());
        return officeValidationCache.get(key, k -> {
            log.debug("Cache miss: loading office from DB for tenantId={}, code={}", k.getTenantId(), k.getOfficeCode());
            return officeRepository.findByTenantIdAndCode(k.getTenantId(), k.getOfficeCode());
        });
    }

    /**
     * Explicitly invalidates the cached entry for a given tenant and office code.
     */
    public void invalidate(UUID tenantId, String code) {
        if (tenantId != null && code != null && !code.isBlank()) {
            TenantOfficeKey key = new TenantOfficeKey(tenantId, code.trim());
            officeValidationCache.invalidate(key);
            log.info("Invalidated office cache entry: tenantId={}, code={}", tenantId, code);
        }
    }

    /**
     * Invalidates all cached offices for a tenant.
     */
    public void invalidateAllForTenant(UUID tenantId) {
        if (tenantId != null) {
            officeValidationCache.asMap().keySet().removeIf(k -> k.getTenantId().equals(tenantId));
            log.info("Invalidated all office cache entries for tenantId={}", tenantId);
        }
    }

    /**
     * Deactivates an office in the database and purges its cache entry.
     */
    @Transactional
    public boolean deactivateOffice(UUID tenantId, String code) {
        Optional<Office> opt = officeRepository.findByTenantIdAndCode(tenantId, code.trim());
        if (opt.isEmpty()) {
            return false;
        }
        Office office = opt.get();
        office.setActive(false);
        officeRepository.save(office);

        invalidate(tenantId, code);
        return true;
    }

    /**
     * Saves or updates an office in the database and invalidates its cache entry.
     */
    @Transactional
    public Office saveOffice(Office office) {
        Office saved = officeRepository.save(office);
        invalidate(saved.getTenantId(), saved.getCode());
        return saved;
    }

    /**
     * Exposes cache statistics for monitoring.
     */
    public CacheStats getStats() {
        return officeValidationCache.stats();
    }
}
