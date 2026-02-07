package com.opayque.api.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/// Epic 1: Security Hardening - Log Sanitization Unit Testing.
///
/// Provides isolated unit testing for the PiiLoggingConverter to ensure
/// robust sanitization of PII within the Logback pipeline.
/// This suite verifies guard clauses, edge cases for short identifiers,
/// and standard masking paths to prevent regression in the privacy engine.
@ExtendWith(MockitoExtension.class)
class PiiLoggingConverterTest {

    @Mock
    private ILoggingEvent event;

    private final PiiLoggingConverter converter = new PiiLoggingConverter();

    /// Verifies the internal guard clause for null log messages.
    ///
    /// Ensures the converter returns an empty string instead of throwing a
    /// NullPointerException when the log event contains no message body.
    @Test
    @DisplayName("Unit: Should return empty string for null message (Coverage: Guard Clause)")
    void shouldHandleNullMessage() {
        when(event.getFormattedMessage()).thenReturn(null);
        assertEquals("", converter.convert(event));
    }

    /// Verifies the internal guard clause for empty log messages.
    ///
    /// Ensures that empty strings are returned directly without triggering
    /// unnecessary regex engine overhead.
    @Test
    @DisplayName("Unit: Should return empty string for empty message (Coverage: Guard Clause)")
    void shouldHandleEmptyMessage() {
        when(event.getFormattedMessage()).thenReturn("");
        assertEquals("", converter.convert(event));
    }

    /// Validates the edge-case masking logic for single-character local parts.
    ///
    /// Redacts the entire local part if its length is ≤ 1 to prevent identity
    /// discovery via minimal character exposure.
    @Test
    @DisplayName("Unit: Should mask short email local parts (Coverage: Edge Case)")
    void shouldMaskShortLocalPart() {
        when(event.getFormattedMessage()).thenReturn("User a@opayque.com login");
        assertEquals("User ***@opayque.com login", converter.convert(event));
    }

    /// Validates the standard redaction path for multi-character local parts.
    ///
    /// Confirms that only the initial character is preserved followed by
    /// masking characters, maintaining the standard "Opaque" log format.
    @Test
    @DisplayName("Unit: Should mask standard email local parts (Coverage: Standard Path)")
    void shouldMaskStandardEmail() {
        when(event.getFormattedMessage()).thenReturn("User dev@opayque.com login");
        assertEquals("User d***@opayque.com login", converter.convert(event));
    }

  /**
   * Validates the PII masking engine's ability to sanitize email identifiers across multiple log
   * format conventions used in FinTech audit trails.
   *
   * <p>This test ensures regulatory compliance (PCI-DSS, GDPR Article 32) by verifying that both
   * legacy plain-text and modern bracketed notation formats receive consistent data anonymization
   * treatment. The converter must preserve the email domain for transaction correlation while
   * applying irreversible masking to the local part.
   *
   * <p>Test Coverage: - Legacy Format: "User: email@domain.com" - Common in payment gateway logs -
   * Bracketed Format: "User: [email@domain.com]" - Used in structured audit events
   *
   * <p>Security Implications: - Prevents account enumeration attacks through log analysis -
   * Maintains referential integrity for fraud detection systems - Ensures consistent PII handling
   * across microservice boundaries
   *
   * <p>FinTech Context: - Required for PCI-DSS Requirement 3.4 (PAN protection in logs) - Supports
   * AML/KYC audit requirements for transaction attribution - Compatible with SIEM correlation rules
   * using email domains
   */
  @Test
  @DisplayName("Unit: Should support BOTH standard and bracketed log formats")
  void shouldSupportDualFormats() {
        // 1. Old Style (Standard)
        when(event.getFormattedMessage()).thenReturn("User: old@opayque.com login");
        assertEquals("User: o***@opayque.com login", converter.convert(event));

        // 2. New Style (Brackets)
        when(event.getFormattedMessage()).thenReturn("User: [new@opayque.com] login");
        assertEquals("User: [n***@opayque.com] login", converter.convert(event));
    }
}