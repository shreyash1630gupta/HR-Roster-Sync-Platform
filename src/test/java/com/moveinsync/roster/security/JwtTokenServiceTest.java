package com.moveinsync.roster.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JWT Token Service Edge Case Tests (Phase 5)")
class JwtTokenServiceTest {

    private JwtTokenService jwtTokenService;
    private ObjectMapper objectMapper;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jwtTokenService = new JwtTokenService(TestJwtUtils.TEST_SECRET, objectMapper);
        tenantId = UUID.randomUUID();
    }

    @Test
    @DisplayName("Valid token roundtrip: successfully generates and parses claims")
    void shouldGenerateAndParseValidToken() {
        String token = jwtTokenService.generateToken(tenantId, UserRole.HR_SYSTEM, "hr-client-1", Duration.ofHours(1));
        assertNotNull(token);

        TenantContext ctx = jwtTokenService.validateAndParse(token);
        assertNotNull(ctx);
        assertEquals(tenantId, ctx.getTenantId());
        assertEquals("hr-client-1", ctx.getUserId());
        assertEquals(UserRole.HR_SYSTEM, ctx.getRole());
    }

    @Test
    @DisplayName("Edge Case: Null or empty token throws SecurityException")
    void shouldRejectNullOrEmptyToken() {
        assertThrows(SecurityException.class, () -> jwtTokenService.validateAndParse(null));
        assertThrows(SecurityException.class, () -> jwtTokenService.validateAndParse(""));
        assertThrows(SecurityException.class, () -> jwtTokenService.validateAndParse("   "));
    }

    @Test
    @DisplayName("Edge Case: Malformed dot-delimited format (1, 2, or 4 parts) throws SecurityException")
    void shouldRejectMalformedTokenStructure() {
        assertThrows(SecurityException.class, () -> jwtTokenService.validateAndParse("single-part"));
        assertThrows(SecurityException.class, () -> jwtTokenService.validateAndParse("part1.part2"));
        assertThrows(SecurityException.class, () -> jwtTokenService.validateAndParse("p1.p2.p3.p4"));
    }

    @Test
    @DisplayName("Edge Case: Tampered header breaks HMAC signature")
    void shouldRejectTamperedHeader() {
        String token = jwtTokenService.generateToken(tenantId, UserRole.ADMIN, "admin", Duration.ofHours(1));
        String[] parts = token.split("\\.");

        // Tamper header
        String fakeHeader = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String tamperedToken = fakeHeader + "." + parts[1] + "." + parts[2];

        assertThrows(SecurityException.class, () -> jwtTokenService.validateAndParse(tamperedToken));
    }

    @Test
    @DisplayName("Edge Case: Tampered payload claim breaks HMAC signature")
    void shouldRejectTamperedPayload() {
        String token = jwtTokenService.generateToken(tenantId, UserRole.HR_SYSTEM, "hr", Duration.ofHours(1));
        String[] parts = token.split("\\.");

        // Alter payload to elevate to ADMIN
        String elevatedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("{\"tenant_id\":\"" + tenantId + "\",\"role\":\"ADMIN\",\"sub\":\"hr\",\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}").getBytes(StandardCharsets.UTF_8));
        String tamperedToken = parts[0] + "." + elevatedPayload + "." + parts[2];

        assertThrows(SecurityException.class, () -> jwtTokenService.validateAndParse(tamperedToken));
    }

    @Test
    @DisplayName("Edge Case: Different secret key fails signature verification")
    void shouldRejectTokenSignedWithDifferentSecret() {
        JwtTokenService otherService = new JwtTokenService("DifferentSecretKeyWithSufficientLength32BytesMin!", objectMapper);
        String foreignToken = otherService.generateToken(tenantId, UserRole.HR_SYSTEM, "hr", Duration.ofHours(1));

        SecurityException ex = assertThrows(SecurityException.class, () -> jwtTokenService.validateAndParse(foreignToken));
        assertTrue(ex.getMessage().contains("Invalid JWT signature"));
    }

    @Test
    @DisplayName("Edge Case: Expired token throws SecurityException with expiration message")
    void shouldRejectExpiredToken() {
        String expiredToken = jwtTokenService.generateToken(tenantId, UserRole.TRANSPORT_ADMIN, "admin", Duration.ofSeconds(-30));

        SecurityException ex = assertThrows(SecurityException.class, () -> jwtTokenService.validateAndParse(expiredToken));
        assertTrue(ex.getMessage().contains("JWT token has expired"));
    }

    @Test
    @DisplayName("Edge Case: Missing exp claim throws SecurityException")
    void shouldRejectTokenMissingExpClaim() {
        String payloadJson = "{\"tenant_id\":\"" + tenantId + "\",\"role\":\"HR_SYSTEM\",\"sub\":\"user\"}";
        String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String headerB64 = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

        JwtTokenService testService = new JwtTokenService(TestJwtUtils.TEST_SECRET, objectMapper);
        // Sign this manually
        String token = testService.generateToken(tenantId, UserRole.HR_SYSTEM, "sub", Duration.ofHours(1));
        String[] parts = token.split("\\.");
        // Malformed payload without exp
        String unsigned = parts[0] + "." + payloadB64;

        assertThrows(SecurityException.class, () -> jwtTokenService.validateAndParse(unsigned + "." + parts[2]));
    }

    @Test
    @DisplayName("Edge Case: Malformed or missing tenant_id claim throws SecurityException")
    void shouldRejectTokenWithMalformedTenantId() {
        // Token where tenant_id is "not-a-uuid"
        String payloadJson = "{\"tenant_id\":\"not-a-valid-uuid\",\"role\":\"HR_SYSTEM\",\"sub\":\"user\",\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}";
        String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String headerB64 = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

        assertThrows(SecurityException.class, () -> {
            String token = jwtTokenService.generateToken(tenantId, UserRole.HR_SYSTEM, "sub", Duration.ofHours(1));
            jwtTokenService.validateAndParse(headerB64 + "." + payloadB64 + ".fakeSignature");
        });
    }

    @Test
    @DisplayName("Edge Case: Unknown user role throws SecurityException")
    void shouldRejectUnknownUserRole() {
        String payloadJson = "{\"tenant_id\":\"" + tenantId + "\",\"role\":\"SUPERUSER_GOD_MODE\",\"sub\":\"user\",\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}";
        String payloadB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        String headerB64 = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

        assertThrows(SecurityException.class, () -> {
            jwtTokenService.validateAndParse(headerB64 + "." + payloadB64 + ".fakeSignature");
        });
    }
}

