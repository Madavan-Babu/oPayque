package com.opayque.api.infrastructure.logging;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Epic 1: Security Hardening - PCI-DSS Compliant Log Sanitization.
///
/// This converter intercepts raw log messages and surgically redacts Personally
/// Identifiable Information (PII) using optimized regular expressions.
/// It replaces the standard %msg converter in the Logback configuration to ensure
/// that sensitive data never reaches persistent storage in plaintext.
public class PiiLoggingConverter extends MessageConverter {

    /// Compiled pattern for high-frequency log interception.
    /// Matches email structures: Boundary -> Local Part -> @ -> Domain -> Boundary.
    // Improved Regex: Added \[ (open bracket) to the start and \] (close bracket) to the end.
    // Logic: Lookbehind for (Start OR Space OR Quote OR Colon OR Bracket)
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?<=^|\\s|\"|'|:|\\[)([\\w\\.-]+)(@)([\\w\\.-]+)(?=$|\\s|\"|'|\\.|,|\\])");

    /// Core conversion entry point for the Logback pipeline.
    /// Intercepts the formatted message and applies sanitization logic before egress.
    @Override
    public String convert(ILoggingEvent event) {
        String originalMessage = event.getFormattedMessage();
        if (originalMessage == null || originalMessage.isEmpty()) {
            return "";
        }
        return maskEmails(originalMessage);
    }

    /// Performs regex-based substitution on the log string.
    /// Redacts the local part of detected emails while preserving the domain for
    /// operational troubleshooting context.
    private String maskEmails(String message) {
        Matcher matcher = EMAIL_PATTERN.matcher(message);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String fullLocalPart = matcher.group(1);
            String atSymbol = matcher.group(2);
            String domain = matcher.group(3);

            // Sanitization Logic: Truncates local part to the initial character followed by masks.
            String maskedLocal;
            if (fullLocalPart.length() <= 1) {
                maskedLocal = "***";
            } else {
                maskedLocal = fullLocalPart.charAt(0) + "***";
            }

            // Example: "dev@opayque.com" -> "d***@opayque.com"
            matcher.appendReplacement(sb, maskedLocal + atSymbol + domain);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}