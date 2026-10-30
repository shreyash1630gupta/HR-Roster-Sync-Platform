package com.moveinsync.roster.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.enums.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Security & Multi-Tenant Isolation Tests (Phase 5: ST-5.1, ST-5.2, ST-5.3, ST-5.8)")
class SecurityFilterIntegrationTest {

    private JwtTokenService jwtTokenService;
    private JwtAuthenticationFilter authFilter;
    private ObjectMapper objectMapper;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jwtTokenService = new JwtTokenService(TestJwtUtils.TEST_SECRET, objectMapper);
        authFilter = new JwtAuthenticationFilter(jwtTokenService, objectMapper);

        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    @Test
    @DisplayName("ST-5.1: Unauthenticated request to protected endpoint returns HTTP 401 Unauthorized")
    void shouldReturn401WhenNoAuthorizationHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/events");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Missing or malformed Authorization header"));
        assertNull(TenantContextHolder.getContext(), "ThreadLocal must be null");
    }

    @Test
    @DisplayName("ST-5.1: Request with malformed / invalid signature returns HTTP 401 Unauthorized")
    void shouldReturn401WhenInvalidSignature() throws ServletException, IOException {
        String token = jwtTokenService.generateToken(tenantA, UserRole.HR_SYSTEM, "sub", Duration.ofHours(1));
        // Tamper signature
        String tamperedToken = token.substring(0, token.lastIndexOf('.') + 1) + "invalidsignature";

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/events");
        request.addHeader("Authorization", "Bearer " + tamperedToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Invalid JWT signature"));
    }

    @Test
    @DisplayName("ST-5.1: Request with expired token returns HTTP 401 Unauthorized")
    void shouldReturn401WhenExpiredToken() throws ServletException, IOException {
        String expiredToken = jwtTokenService.generateToken(tenantA, UserRole.HR_SYSTEM, "sub", Duration.ofSeconds(-10));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/events");
        request.addHeader("Authorization", "Bearer " + expiredToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("JWT token has expired"));
    }

    @Test
    @DisplayName("ST-5.2: HR_SYSTEM token attempting PUT /transport-fields returns HTTP 403 Forbidden")
    void shouldReturn403WhenHrSystemAttemptsTransportFieldsUpdate() throws ServletException, IOException {
        String hrToken = jwtTokenService.generateToken(tenantA, UserRole.HR_SYSTEM, "hr-service", Duration.ofHours(1));

        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/employees/" + UUID.randomUUID() + "/transport-fields");
        request.addHeader("Authorization", "Bearer " + hrToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("is not authorized to access"));
    }

    @Test
    @DisplayName("ST-5.3: Token for Tenant A attempting to access Tenant B endpoint returns HTTP 403 Forbidden")
    void shouldReturn403WhenTenantMismatch() throws ServletException, IOException {
        String tokenA = jwtTokenService.generateToken(tenantA, UserRole.HR_SYSTEM, "hr-service", Duration.ofHours(1));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/events");
        request.addHeader("Authorization", "Bearer " + tokenA);
        request.addHeader("X-Tenant-ID", tenantB.toString()); // Mismatch: header has tenant B, token is tenant A
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Tenant mismatch"));
        assertNull(TenantContextHolder.getContext(), "ThreadLocal must be cleared even on error");
    }

    @Test
    @DisplayName("ST-5.8: Prometheus endpoint requires OPERATOR or ADMIN role (HR_SYSTEM -> 403)")
    void shouldEnforceOperatorRoleOnPrometheusEndpoint() throws ServletException, IOException {
        String hrToken = jwtTokenService.generateToken(tenantA, UserRole.HR_SYSTEM, "hr-service", Duration.ofHours(1));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "Bearer " + hrToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Role HR_SYSTEM is not authorized"));
    }

    @Test
    @DisplayName("ST-5.8: Prometheus endpoint allows OPERATOR role")
    void shouldAllowOperatorRoleOnPrometheusEndpoint() throws ServletException, IOException {
        String operatorToken = jwtTokenService.generateToken(tenantA, UserRole.OPERATOR, "operator-1", Duration.ofHours(1));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");
        request.addHeader("Authorization", "Bearer " + operatorToken);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNull(TenantContextHolder.getContext(), "ThreadLocal must be cleared after filter completion");
    }

    @Test
    @DisplayName("Whitelisted endpoints (/actuator/health, /api/v1/auth/token) bypass authentication filter")
    void shouldBypassWhitelistedEndpoints() throws ServletException, IOException {
        assertTrue(authFilter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health")));
        assertTrue(authFilter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health/liveness")));
        assertTrue(authFilter.shouldNotFilter(new MockHttpServletRequest("GET", "/actuator/health/readiness")));
        assertTrue(authFilter.shouldNotFilter(new MockHttpServletRequest("POST", "/api/v1/auth/token")));
        assertTrue(authFilter.shouldNotFilter(new MockHttpServletRequest("GET", "/v3/api-docs")));
        assertTrue(authFilter.shouldNotFilter(new MockHttpServletRequest("GET", "/swagger-ui/index.html")));
    }

    @Test
    @DisplayName("TenantContextHolder cleanup: guaranteed ThreadLocal removal after request")
    void shouldCleanUpThreadLocalInFinallyBlock() throws ServletException, IOException {
        String token = jwtTokenService.generateToken(tenantA, UserRole.HR_SYSTEM, "hr-service", Duration.ofHours(1));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/events");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        // Chain verifies that TenantContextHolder is populated during downstream execution
        FilterChain chain = (req, res) -> {
            assertNotNull(TenantContextHolder.getContext());
            assertEquals(tenantA, TenantContextHolder.getTenantId());
            assertEquals(UserRole.HR_SYSTEM, TenantContextHolder.getContext().getRole());
        };

        authFilter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNull(TenantContextHolder.getContext(), "ThreadLocal must be purged in finally block");
    }

    @Test
    @DisplayName("RBAC Edge Case: TRANSPORT_ADMIN cannot trigger event ingestion (returns 403)")
    void shouldBlockTransportAdminFromEventIngestion() throws ServletException, IOException {
        String token = jwtTokenService.generateToken(tenantA, UserRole.TRANSPORT_ADMIN, "trans-admin", Duration.ofHours(1));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/events");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Role TRANSPORT_ADMIN is not authorized"));
    }

    @Test
    @DisplayName("RBAC Edge Case: TRANSPORT_ADMIN cannot upload nightly CSV (returns 403)")
    void shouldBlockTransportAdminFromReconciliationUpload() throws ServletException, IOException {
        String token = jwtTokenService.generateToken(tenantA, UserRole.TRANSPORT_ADMIN, "trans-admin", Duration.ofHours(1));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/reconcile");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Role TRANSPORT_ADMIN is not authorized"));
    }

    @Test
    @DisplayName("RBAC Edge Case: TRANSPORT_ADMIN cannot access outbox DLQ endpoints (returns 403)")
    void shouldBlockTransportAdminFromOutboxDlq() throws ServletException, IOException {
        String token = jwtTokenService.generateToken(tenantA, UserRole.TRANSPORT_ADMIN, "trans-admin", Duration.ofHours(1));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/outbox/dead-letter");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Role TRANSPORT_ADMIN is not authorized"));
    }

    @Test
    @DisplayName("RBAC Edge Case: HR_SYSTEM cannot approve reconciliation runs (returns 403)")
    void shouldBlockHrSystemFromReconciliationApproval() throws ServletException, IOException {
        String token = jwtTokenService.generateToken(tenantA, UserRole.HR_SYSTEM, "hr-client", Duration.ofHours(1));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/reconciliation/" + UUID.randomUUID() + "/approve");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        authFilter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Role HR_SYSTEM is not authorized"));
    }

    @Test
    @DisplayName("RBAC Edge Case: OPERATOR cannot trigger event ingestion or modify transport fields (returns 403)")
    void shouldBlockOperatorFromIngestionAndTransportFields() throws ServletException, IOException {
        String token = jwtTokenService.generateToken(tenantA, UserRole.OPERATOR, "ops-user", Duration.ofHours(1));

        // Attempt event ingestion
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/api/v1/sync/events");
        req1.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res1 = new MockHttpServletResponse();
        authFilter.doFilter(req1, res1, new MockFilterChain());
        assertEquals(403, res1.getStatus());

        // Attempt transport field update
        MockHttpServletRequest req2 = new MockHttpServletRequest("PUT", "/api/v1/employees/" + UUID.randomUUID() + "/transport-fields");
        req2.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse res2 = new MockHttpServletResponse();
        authFilter.doFilter(req2, res2, new MockFilterChain());
        assertEquals(403, res2.getStatus());
    }

    @Test
    @DisplayName("RBAC Edge Case: ADMIN possesses universal access across all endpoints")
    void shouldAllowAdminOnAllEndpoints() throws ServletException, IOException {
        String adminToken = jwtTokenService.generateToken(tenantA, UserRole.ADMIN, "super-admin", Duration.ofHours(1));

        String[] paths = {
                "/api/v1/sync/events",
                "/api/v1/sync/reconcile",
                "/api/v1/employees/" + UUID.randomUUID() + "/transport-fields",
                "/api/v1/reconciliation/" + UUID.randomUUID() + "/approve",
                "/api/v1/outbox/dead-letter",
                "/actuator/prometheus"
        };

        for (String path : paths) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            req.addHeader("Authorization", "Bearer " + adminToken);
            MockHttpServletResponse res = new MockHttpServletResponse();
            authFilter.doFilter(req, res, new MockFilterChain());
            assertEquals(200, res.getStatus(), "ADMIN must have access to: " + path);
        }
    }

    @Test
    @DisplayName("Edge Case: Malformed UUID in X-Tenant-ID header returns HTTP 400 Bad Request")
    void shouldReturn400WhenHeaderUuidIsMalformed() throws ServletException, IOException {
        String token = jwtTokenService.generateToken(tenantA, UserRole.HR_SYSTEM, "hr", Duration.ofHours(1));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/events");
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("X-Tenant-ID", "not-a-valid-uuid-string");
        MockHttpServletResponse response = new MockHttpServletResponse();

        authFilter.doFilter(request, response, new MockFilterChain());

        assertEquals(400, response.getStatus());
        assertTrue(response.getContentAsString().contains("Malformed UUID in X-Tenant-ID header"));
    }

    @Test
    @DisplayName("Edge Case: Non-Bearer Authorization scheme returns HTTP 401 Unauthorized")
    void shouldReturn401WhenNonBearerScheme() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/events");
        request.addHeader("Authorization", "Basic dXNlcjpwYXNzd29yZA==");
        MockHttpServletResponse response = new MockHttpServletResponse();

        authFilter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Missing or malformed Authorization header with Bearer token"));
    }

    @Test
    @DisplayName("Edge Case: ThreadLocal is safely cleared even when downstream chain throws RuntimeException")
    void shouldClearThreadLocalOnDownstreamException() {
        String token = jwtTokenService.generateToken(tenantA, UserRole.HR_SYSTEM, "hr", Duration.ofHours(1));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sync/events");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain faultyChain = (req, res) -> {
            assertNotNull(TenantContextHolder.getContext());
            throw new RuntimeException("Simulated downstream crash");
        };

        assertThrows(RuntimeException.class, () -> authFilter.doFilter(request, response, faultyChain));
        assertNull(TenantContextHolder.getContext(), "ThreadLocal MUST be cleared even after downstream unhandled exception");
    }
}

