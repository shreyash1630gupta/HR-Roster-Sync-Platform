package com.moveinsync.roster.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

import java.util.regex.Pattern;

/**
 * Logback composite converter that masks sensitive PII (emails and 10-digit phone numbers)
 * in formatted log messages before outputting to JSON console appender.
 */
public class PiiMaskingConverter extends CompositeConverter<ILoggingEvent> {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9_.+-]+@[a-zA-Z0-9-]+\\.[a-zA-Z0-9-.]+");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b\\d{10}\\b");

    @Override
    protected String transform(ILoggingEvent event, String in) {
        if (in == null || in.isEmpty()) {
            return in;
        }
        String masked = EMAIL_PATTERN.matcher(in).replaceAll("[REDACTED_EMAIL]");
        return PHONE_PATTERN.matcher(masked).replaceAll("[REDACTED_PHONE]");
    }
}

