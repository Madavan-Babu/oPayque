package com.opayque.api.infrastructure.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.identity.dto.LoginRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/// Epic 1: Security Hardening - Log Interception & PII Leak Prevention.
///
/// This integration suite verifies the efficacy of the PiiLoggingConverter within the
/// Spring Boot context. It ensures that sensitive data processed by
/// the identity layer is surgically masked before persisting to the console or log
/// files, fulfilling the "Opaque" logging requirement.
///
/// Governance: Aligns with PCI-DSS Requirement 3 (Protect stored cardholder data)
/// and GDPR principles regarding data minimization.
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class LogMaskingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    /// Verifies that PII is programmatically redacted in application logs.
    ///
    /// This test utilizes OutputCaptureExtension to intercept the standard output
    /// stream after triggering an authentication flow. It asserts that
    /// while the system records the event, the raw email identity is never leaked
    /// in plaintext.
    ///
    /// @param output The captured system output stream.
    /// @throws Exception If the MockMvc request execution fails.
    @Test
    @DisplayName("Integration: PII must be masked in application logs")
    void shouldMaskPiiInLogs(CapturedOutput output) throws Exception {
        // Arrange: Construct a request containing sensitive PII.
        String sensitiveEmail = "leaky-email@opayque.com";
        LoginRequest request = new LoginRequest(sensitiveEmail, "password123");

        // Act: Invoke the login endpoint which triggers the AuthService logging:
        // "Attempting login for user: {}".
        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));

        // Assert 1: The logs must contain the redacted signature.
        // Verification: "leaky-email@opayque.com" -> "l***@opayque.com".
        assertTrue(output.getOut().contains("l***@opayque.com"),
                "Logs did not contain the masked email signature.");

        // Assert 2: The logs must NOT contain the original PII (The Anti-Leak Check).
        // This is a critical security gate to prevent accidental PII persistence.
        assertFalse(output.getOut().contains(sensitiveEmail),
                "CRITICAL FAILURE: Raw email was found in application logs!");
    }
}