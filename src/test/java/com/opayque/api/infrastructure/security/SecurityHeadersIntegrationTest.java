package com.opayque.api.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// Epic 1: Security Hardening - Security Headers Verification.
///
/// This integration suite validates that the oPayque API correctly injects mandatory
/// security headers into every HTTP response. These headers are critical for
/// mitigating common web-based attack vectors such as XSS, Clickjacking, and
/// Man-In-The-Middle (MITM) attacks.
///
/// Governance: Enforces compliance with OWASP Secure Headers Project and
/// PCI-DSS requirements for data in transit and browser-side caching.
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class SecurityHeadersIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    /// Validates the enforcement of strict security and cache-control headers.
    ///
    /// This test simulates a request to a non-existent endpoint to verify that security
    /// headers are present even on error responses (404). It specifically checks for:
    /// 1. Anti-caching directives to protect sensitive banking data.
    /// 2. HSTS for mandatory TLS enforcement.
    /// 3. Frame options and CSP for UI integrity.
    ///
    /// @throws Exception If the MockMvc request execution fails.
    @Test
    @DisplayName("Integration: API must enforce strict OWASP and No-Cache headers")
    void shouldEnforceSecurityHeaders() throws Exception {
        // We utilize .secure(true) to simulate an HTTPS connection, which is a
        // prerequisite for the HSTS header to be emitted by Spring Security.
        mockMvc.perform(get("/api/v1/security-check").secure(true))
                .andExpect(status().isNotFound())

                // 1. The "No-Cache" Mandate (Critical for Fintech)
                // Prevents sensitive financial data from being stored in local browser
                // caches or intermediate proxies.
                .andExpect(header().string("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(header().string("Expires", "0"))

                // 2. Transport Security (HSTS)
                // Enforces HTTPS for 1 year (31,536,000 seconds) to mitigate MITM.
                .andExpect(header().string("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains"))

                // 3. Content Sniffing Protection
                // Disables MIME-sniffing to prevent browsers from interpreting files as
                // a different MIME type than what is specified.
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))

                // 4. Clickjacking Protection
                // Disallows the API response from being embedded in frames, iframes,
                // or objects to prevent UI redressing.
                .andExpect(header().string("X-Frame-Options", "DENY"))

                // 5. XSS Protection (Defense in Depth)
                // Configures legacy browser XSS filters to block the page if an
                // attack is detected.
                .andExpect(header().string("X-XSS-Protection", "1; mode=block"))

                // 6. Content Security Policy (Script Restriction)
                // Restricts content to the origin and prevents frame embedding
                // by third-party sites.
                .andExpect(header().string("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none';"));
    }
}