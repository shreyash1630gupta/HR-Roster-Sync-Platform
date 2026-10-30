package com.moveinsync.roster.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moveinsync.roster.domain.enums.UserRole;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Security filter that enforces JWT authentication, RBAC authorization,
 * and tenant boundary isolation across all protected API endpoints.
 */
@Slf4j
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    private static final List<String> WHITELISTED_PATTERNS = List.of(
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/actuator/health/**",
            "/api/v1/auth/**"
    );

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        for (String pattern : WHITELISTED_PATTERNS) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "Missing or malformed Authorization header with Bearer token");
            return;
        }

        String token = authHeader.substring(7).trim();
        TenantContext tenantContext;
        try {
            tenantContext = jwtTokenService.validateAndParse(token);
        } catch (SecurityException se) {
            log.warn("JWT authentication failed: {}", se.getMessage());
            sendErrorResponse(response, HttpStatus.UNAUTHORIZED, "Invalid or expired JWT token: " + se.getMessage());
            return;
        }

        // Enforce Tenant Isolation on Header (if provided)
        String headerTenantId = request.getHeader("X-Tenant-ID");
        if (headerTenantId != null && !headerTenantId.isBlank()) {
            try {
                UUID headerUuid = UUID.fromString(headerTenantId.trim());
                if (!headerUuid.equals(tenantContext.getTenantId())) {
                    log.warn("Tenant isolation violation: header tenantId {} does not match token tenantId {}",
                            headerTenantId, tenantContext.getTenantId());
                    sendErrorResponse(response, HttpStatus.FORBIDDEN, "Tenant mismatch: X-Tenant-ID header does not match token tenant_id");
                    return;
                }
            } catch (IllegalArgumentException iae) {
                sendErrorResponse(response, HttpStatus.BAD_REQUEST, "Malformed UUID in X-Tenant-ID header");
                return;
            }
        }

        // Enforce Role-Based Access Control (RBAC)
        String path = request.getRequestURI();
        String method = request.getMethod();

        if (!isAuthorized(path, method, tenantContext.getRole())) {
            log.warn("RBAC access denied: role {} is not authorized for {} {}",
                    tenantContext.getRole(), method, path);
            sendErrorResponse(response, HttpStatus.FORBIDDEN,
                    String.format("Role %s is not authorized to access %s %s", tenantContext.getRole(), method, path));
            return;
        }

        // Bind Context to ThreadLocal & MDC
        TenantContextHolder.setContext(tenantContext);
        MDC.put("tenant_id", tenantContext.getTenantId().toString());

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Guaranteed cleanup after every request
            TenantContextHolder.clear();
            MDC.remove("tenant_id");
        }
    }

    private boolean isAuthorized(String path, String method, UserRole role) {
        if (role == UserRole.ADMIN) {
            return true; // ADMIN possesses all permissions
        }

        // Prometheus Metrics endpoint: OPERATOR or ADMIN only
        if (pathMatcher.match("/actuator/prometheus", path)) {
            return role == UserRole.OPERATOR;
        }

        // Transport Admin Field Updates: TRANSPORT_ADMIN or ADMIN only
        if (pathMatcher.match("/api/v1/employees/*/transport-fields", path)) {
            return role == UserRole.TRANSPORT_ADMIN;
        }

        // Event Ingestion: HR_SYSTEM or ADMIN only
        if (pathMatcher.match("/api/v1/sync/events", path)) {
            return role == UserRole.HR_SYSTEM;
        }

        // Nightly CSV Reconciliation Upload: HR_SYSTEM or ADMIN only
        if (pathMatcher.match("/api/v1/sync/reconcile", path) || pathMatcher.match("/api/v1/reconciliation/upload", path)) {
            return role == UserRole.HR_SYSTEM;
        }

        // Employee Queries: TRANSPORT_ADMIN or OPERATOR or ADMIN
        if (pathMatcher.match("/api/v1/employees/**", path)) {
            return role == UserRole.TRANSPORT_ADMIN || role == UserRole.OPERATOR;
        }

        // Reconciliation Review & Approval: OPERATOR or ADMIN only
        if (pathMatcher.match("/api/v1/reconciliation/**", path)) {
            return role == UserRole.OPERATOR;
        }

        // Outbox & DLQ Operator Actions: OPERATOR or ADMIN only
        if (pathMatcher.match("/api/v1/outbox/**", path)) {
            return role == UserRole.OPERATOR;
        }

        // Office Management: OPERATOR or ADMIN
        if (pathMatcher.match("/api/v1/offices/**", path)) {
            return role == UserRole.OPERATOR || role == UserRole.TRANSPORT_ADMIN;
        }

        return true;
    }

    private void sendErrorResponse(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = Map.of(
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message
        );
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}

