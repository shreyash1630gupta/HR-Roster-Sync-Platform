package com.moveinsync.roster.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Structured JSON Logging & PII Masking Verification (Phase 5: IT-5.9)")
class LoggingAndPiiMaskingTest {

    private static final String LOG_PATTERN = "{\"timestamp\":\"%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX}\",\"level\":\"%level\",\"logger\":\"%logger{36}\",\"message\":\"%maskPii(%msg)\",\"correlation_id\":\"%X{correlation_id:-none}\",\"tenant_id\":\"%X{tenant_id:-none}\"}%n";

    private Logger logger;
    private ByteArrayOutputStream outputStream;
    private OutputStreamAppender<ILoggingEvent> appender;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        PatternLayout.DEFAULT_CONVERTER_MAP.put("maskPii", PiiMaskingConverter.class.getName());

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        logger = context.getLogger("com.moveinsync.roster.test.PiiTestLogger");

        PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setContext(context);
        encoder.setPattern(LOG_PATTERN);
        encoder.start();

        outputStream = new ByteArrayOutputStream();
        appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(outputStream);
        appender.start();

        logger.addAppender(appender);
        logger.setAdditive(false);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        if (appender != null) {
            appender.stop();
        }
    }

    @Test
    @DisplayName("IT-5.9: Log output is valid JSON, contains correlation_id and tenant_id, and redacts PII")
    void shouldFormatAsJsonAndRedactPii() throws Exception {
        String correlationId = "corr-98765-abcdef";
        String tenantId = "11111111-2222-3333-4444-555555555555";
        MDC.put("correlation_id", correlationId);
        MDC.put("tenant_id", tenantId);

        String rawEmail = "sarah.connor@sky-net.io";
        String rawPhone = "9876543210";
        String sensitiveMessage = "Employee profile synced for " + rawEmail + " with mobile " + rawPhone;

        logger.info(sensitiveMessage);

        String logOutput = outputStream.toString(StandardCharsets.UTF_8).trim();
        assertFalse(logOutput.isBlank(), "Log output must not be empty");

        // Step 1: Verify valid JSON
        JsonNode json = objectMapper.readTree(logOutput);
        assertNotNull(json, "Log line must parse as valid JSON");

        // Step 2: Verify correlation_id and tenant_id populated from MDC
        assertEquals(correlationId, json.get("correlation_id").asText());
        assertEquals(tenantId, json.get("tenant_id").asText());
        assertEquals("INFO", json.get("level").asText());

        // Step 3: Verify PII redaction
        String renderedMessage = json.get("message").asText();
        assertTrue(renderedMessage.contains("[REDACTED_EMAIL]"), "Email must be replaced with [REDACTED_EMAIL]");
        assertTrue(renderedMessage.contains("[REDACTED_PHONE]"), "Phone number must be replaced with [REDACTED_PHONE]");

        // Step 4: Strict assertion - zero PII in log text
        assertFalse(renderedMessage.contains(rawEmail), "Log message must NOT contain raw email address");
        assertFalse(renderedMessage.contains(rawPhone), "Log message must NOT contain raw 10-digit phone number");
        assertFalse(logOutput.contains(rawEmail), "Entire log line must NOT contain raw email");
        assertFalse(logOutput.contains(rawPhone), "Entire log line must NOT contain raw phone");
    }

    @Test
    @DisplayName("IT-5.9: Direct unit verification of PiiMaskingConverter")
    void shouldDirectlyRedactPiiWithConverter() {
        PiiMaskingConverter converter = new PiiMaskingConverter();
        String result = converter.transform(null, "Contact test.user+misc@example.co.uk or 9123456789 for help.");
        assertEquals("Contact [REDACTED_EMAIL] or [REDACTED_PHONE] for help.", result);
    }

    @Test
    @DisplayName("Edge Case: Multi-PII message with multiple emails and multiple phones")
    void shouldRedactMultipleEmailsAndPhones() {
        PiiMaskingConverter converter = new PiiMaskingConverter();
        String input = "Primary: alice@work.com (9876543210), Secondary: bob.smith@work.org (8765432109).";
        String expected = "Primary: [REDACTED_EMAIL] ([REDACTED_PHONE]), Secondary: [REDACTED_EMAIL] ([REDACTED_PHONE]).";
        assertEquals(expected, converter.transform(null, input));
    }

    @Test
    @DisplayName("Edge Case: Numbers that are NOT 10-digit phones (e.g. 5 digits, 9 digits, 12 digits) are preserved")
    void shouldNotMaskNonPhoneNumbers() {
        PiiMaskingConverter converter = new PiiMaskingConverter();
        String input = "Port: 8080, EmpId: 12345, Zip: 560100, Version: 123456789012, 9-digits: 123456789";
        assertEquals(input, converter.transform(null, input), "Non-10-digit numbers must not be masked");
    }

    @Test
    @DisplayName("Edge Case: Null and empty strings are handled safely without NPE")
    void shouldHandleNullAndEmptySafely() {
        PiiMaskingConverter converter = new PiiMaskingConverter();
        assertNull(converter.transform(null, null));
        assertEquals("", converter.transform(null, ""));
    }

    @Test
    @DisplayName("IT-5.9: Default values 'none' when MDC correlation_id or tenant_id are omitted")
    void shouldFallbackToNoneWhenMdcMissing() throws Exception {
        logger.warn("System reboot scheduled");

        String logOutput = outputStream.toString(StandardCharsets.UTF_8).trim();
        JsonNode json = objectMapper.readTree(logOutput);

        assertEquals("none", json.get("correlation_id").asText());
        assertEquals("none", json.get("tenant_id").asText());
        assertEquals("WARN", json.get("level").asText());
        assertEquals("System reboot scheduled", json.get("message").asText());
    }
}
