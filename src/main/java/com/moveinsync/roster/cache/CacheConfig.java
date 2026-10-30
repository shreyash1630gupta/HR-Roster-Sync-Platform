package com.moveinsync.roster.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.moveinsync.roster.domain.entity.Office;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    /**
     * Bounded tenant-scoped Caffeine cache for office validation lookups.
     * 5-minute TTL, maximum 10,000 entries, with stats recording.
     */
    @Bean
    public Cache<TenantOfficeKey, Optional<Office>> officeValidationCache() {
        return Caffeine.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .maximumSize(10_000)
                .recordStats()
                .build();
    }
}
