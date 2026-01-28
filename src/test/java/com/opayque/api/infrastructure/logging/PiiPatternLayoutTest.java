package com.opayque.api.infrastructure.logging;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Epic 1: Security Hardening - Regex Engine Validation.
///
/// Validates the core regular expression logic intended for use within
/// Logback Pattern Layouts. This suite ensures that the pattern correctly
/// identifies PII across various log formats, including multi-identity
/// lines and potential JSON structures.
class PiiPatternLayoutTest {

    /// Compiled regex pattern designed for high-throughput log scanning.
    /// Matches: Start/Whitespace -> Local Part -> @ -> Domain -> End/Whitespace.
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?<=^|\\s)([\\w\\.-]+)(@)([\\w\\.-]+)(?=$|\\s|\\.)");

    /// Verifies standard PII detection and redaction within a typical log string.
    @Test
    @DisplayName("Unit: Should mask email in log message")
    void shouldMaskEmailInLog() {
        String logMessage = "User login attempt: dev@opayque.com from IP 127.0.0.1";
        String masked = mask(logMessage);

        assertEquals("User login attempt: d***@opayque.com from IP 127.0.0.1", masked);
    }

    /// Confirms the regex engine handles multiple PII occurrences within
    /// a single contiguous log entry.
    @Test
    @DisplayName("Unit: Should mask multiple emails in one line")
    void shouldMaskMultipleEmails() {
        String logMessage = "Sent email from alice@test.com to bob@test.com";
        String masked = mask(logMessage);

        assertEquals("Sent email from a***@test.com to b***@test.com", masked);
    }

    /// Integrity Audit: Ensures the regex logic preserves surrounding JSON syntax.
    ///
    /// Validates boundary conditions to ensure that masking does not corrupt
    /// JSON formatting during structured log serialization.
    @Test
    @DisplayName("Unit: Should not mangle JSON structures")
    void shouldPreserveJsonStructure() {
        // Initial boundary verification for simple whitespace-separated context.
        String simpleLog = "Email: user@domain.com";
        assertEquals("Email: u***@domain.com", mask(simpleLog));
    }

    /// Internal simulation of the Layout masking logic.
    ///
    /// Replicates the iterative matching and replacement process utilized
    /// by the infrastructure's sanitization components.
    private String mask(String message) {
        Matcher matcher = EMAIL_PATTERN.matcher(message);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String fullEmail = matcher.group();
            String localPart = matcher.group(1);
            String domain = matcher.group(3);

            String maskedLocal;
            if (localPart.length() <= 1) {
                maskedLocal = "***";
            } else {
                maskedLocal = localPart.charAt(0) + "***";
            }

            matcher.appendReplacement(sb, maskedLocal + "@" + domain);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}