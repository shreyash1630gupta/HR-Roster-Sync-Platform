package com.moveinsync.roster.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.roster.domain.entity.Office;
import com.moveinsync.roster.repository.OfficeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Office Caffeine Cache & Invalidation Tests (Phase 5: IT-5.5, IT-5.6)")
class OfficeCacheServiceTest {

    @Mock
    private OfficeRepository officeRepository;

    private Cache<TenantOfficeKey, Optional<Office>> cache;
    private OfficeCacheService officeCacheService;

    private UUID tenantA;
    private UUID tenantB;
    private final String officeCode = "BLR-EC";

    @BeforeEach
    void setUp() {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        cache = Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .recordStats()
                .build();

        officeCacheService = new OfficeCacheService(officeRepository, cache);
    }

    @Test
    @DisplayName("IT-5.5: Office code lookup hits Caffeine cache after first invocation (DB query count = 1)")
    void shouldHitCacheAfterFirstInvocation() {
        Office office = Office.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantA)
                .code(officeCode)
                .name("Electronic City")
                .active(true)
                .build();

        when(officeRepository.findByTenantIdAndCode(tenantA, officeCode))
                .thenReturn(Optional.of(office));

        // Call 1: Cache Miss -> Queries DB
        Optional<Office> res1 = officeCacheService.getOffice(tenantA, officeCode);
        assertTrue(res1.isPresent());
        assertEquals("Electronic City", res1.get().getName());
        verify(officeRepository, times(1)).findByTenantIdAndCode(tenantA, officeCode);

        // Call 2: Cache Hit -> Serves from memory, zero additional DB queries
        Optional<Office> res2 = officeCacheService.getOffice(tenantA, officeCode);
        assertTrue(res2.isPresent());
        assertEquals("Electronic City", res2.get().getName());
        verify(officeRepository, times(1)).findByTenantIdAndCode(tenantA, officeCode);

        // Call 3: Cache Hit again
        Optional<Office> res3 = officeCacheService.getOffice(tenantA, officeCode);
        assertTrue(res3.isPresent());
        verify(officeRepository, times(1)).findByTenantIdAndCode(tenantA, officeCode);

        assertEquals(2, officeCacheService.getStats().hitCount(), "Cache must record 2 hits");
        assertEquals(1, officeCacheService.getStats().missCount(), "Cache must record 1 miss");
    }

    @Test
    @DisplayName("IT-5.6: Office deactivation purges affected tenant's cache entry")
    void shouldInvalidateCacheOnOfficeDeactivation() {
        Office office = Office.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantA)
                .code(officeCode)
                .name("Electronic City")
                .active(true)
                .build();

        when(officeRepository.findByTenantIdAndCode(tenantA, officeCode))
                .thenReturn(Optional.of(office));

        // Prime cache
        officeCacheService.getOffice(tenantA, officeCode);
        verify(officeRepository, times(1)).findByTenantIdAndCode(tenantA, officeCode);

        // Deactivate office
        boolean deactivated = officeCacheService.deactivateOffice(tenantA, officeCode);
        assertTrue(deactivated);
        assertFalse(office.isActive());
        verify(officeRepository, times(1)).save(office);

        // Next lookup must miss cache and query DB again
        Office deactivatedOfficeInDb = Office.builder()
                .id(office.getId())
                .tenantId(tenantA)
                .code(officeCode)
                .active(false)
                .build();
        when(officeRepository.findByTenantIdAndCode(tenantA, officeCode))
                .thenReturn(Optional.of(deactivatedOfficeInDb));

        Optional<Office> res = officeCacheService.getOffice(tenantA, officeCode);
        assertTrue(res.isPresent());
        assertFalse(res.get().isActive());
        verify(officeRepository, times(3)).findByTenantIdAndCode(tenantA, officeCode);
    }

    @Test
    @DisplayName("Tenant Isolation in Cache: Same office code across Tenant A and Tenant B is isolated")
    void shouldIsolateSameOfficeCodeAcrossDifferentTenants() {
        Office officeA = Office.builder().id(UUID.randomUUID()).tenantId(tenantA).code(officeCode).name("A Campus").active(true).build();
        Office officeB = Office.builder().id(UUID.randomUUID()).tenantId(tenantB).code(officeCode).name("B Campus").active(true).build();

        when(officeRepository.findByTenantIdAndCode(tenantA, officeCode)).thenReturn(Optional.of(officeA));
        when(officeRepository.findByTenantIdAndCode(tenantB, officeCode)).thenReturn(Optional.of(officeB));

        Optional<Office> a = officeCacheService.getOffice(tenantA, officeCode);
        Optional<Office> b = officeCacheService.getOffice(tenantB, officeCode);

        assertEquals("A Campus", a.get().getName());
        assertEquals("B Campus", b.get().getName());

        verify(officeRepository, times(1)).findByTenantIdAndCode(tenantA, officeCode);
        verify(officeRepository, times(1)).findByTenantIdAndCode(tenantB, officeCode);
    }

    @Test
    @DisplayName("Edge Case: Null or blank parameters return Optional.empty() without querying database")
    void shouldReturnEmptyOnNullOrBlankParameters() {
        assertTrue(officeCacheService.getOffice(null, officeCode).isEmpty());
        assertTrue(officeCacheService.getOffice(tenantA, null).isEmpty());
        assertTrue(officeCacheService.getOffice(tenantA, "").isEmpty());
        assertTrue(officeCacheService.getOffice(tenantA, "   ").isEmpty());

        verifyNoInteractions(officeRepository);
    }

    @Test
    @DisplayName("Edge Case: saveOffice updates repository and invalidates cache entry")
    void shouldInvalidateCacheOnSaveOffice() {
        Office office = Office.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantA)
                .code(officeCode)
                .name("Electronic City Original")
                .active(true)
                .build();

        when(officeRepository.findByTenantIdAndCode(tenantA, officeCode)).thenReturn(Optional.of(office));

        // Prime cache
        officeCacheService.getOffice(tenantA, officeCode);
        verify(officeRepository, times(1)).findByTenantIdAndCode(tenantA, officeCode);

        // Update office via saveOffice
        Office updatedOffice = Office.builder()
                .id(office.getId())
                .tenantId(tenantA)
                .code(officeCode)
                .name("Electronic City Renovated")
                .active(true)
                .build();

        when(officeRepository.save(updatedOffice)).thenReturn(updatedOffice);
        officeCacheService.saveOffice(updatedOffice);
        verify(officeRepository, times(1)).save(updatedOffice);

        // Next lookup must miss cache and query DB
        when(officeRepository.findByTenantIdAndCode(tenantA, officeCode)).thenReturn(Optional.of(updatedOffice));
        Optional<Office> result = officeCacheService.getOffice(tenantA, officeCode);
        assertTrue(result.isPresent());
        assertEquals("Electronic City Renovated", result.get().getName());
        verify(officeRepository, times(2)).findByTenantIdAndCode(tenantA, officeCode);
    }

    @Test
    @DisplayName("Edge Case: invalidateAllForTenant clears all tenant entries while preserving other tenants")
    void shouldInvalidateAllForTenantWithoutAffectingOtherTenants() {
        Office officeA = Office.builder().id(UUID.randomUUID()).tenantId(tenantA).code("OFFICE-A").name("Tenant A Office").active(true).build();
        Office officeB = Office.builder().id(UUID.randomUUID()).tenantId(tenantB).code("OFFICE-B").name("Tenant B Office").active(true).build();

        when(officeRepository.findByTenantIdAndCode(tenantA, "OFFICE-A")).thenReturn(Optional.of(officeA));
        when(officeRepository.findByTenantIdAndCode(tenantB, "OFFICE-B")).thenReturn(Optional.of(officeB));

        // Prime both
        officeCacheService.getOffice(tenantA, "OFFICE-A");
        officeCacheService.getOffice(tenantB, "OFFICE-B");

        verify(officeRepository, times(1)).findByTenantIdAndCode(tenantA, "OFFICE-A");
        verify(officeRepository, times(1)).findByTenantIdAndCode(tenantB, "OFFICE-B");

        // Invalidate all for tenant A
        officeCacheService.invalidateAllForTenant(tenantA);

        // Lookup tenant A: misses cache -> DB query count = 2
        officeCacheService.getOffice(tenantA, "OFFICE-A");
        verify(officeRepository, times(2)).findByTenantIdAndCode(tenantA, "OFFICE-A");

        // Lookup tenant B: hits cache -> DB query count stays 1
        officeCacheService.getOffice(tenantB, "OFFICE-B");
        verify(officeRepository, times(1)).findByTenantIdAndCode(tenantB, "OFFICE-B");
    }
}

