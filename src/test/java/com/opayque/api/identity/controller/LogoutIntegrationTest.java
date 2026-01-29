package com.opayque.api.identity.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.identity.dto.LoginRequest;
import com.opayque.api.identity.dto.RegisterRequest;
import com.opayque.api.identity.repository.RefreshTokenRepository;
import com.opayque.api.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// Epic 1: Identity & Access Management - Logout Integration Testing
///
/// This suite validates the lifecycle of the **"Kill Switch"** (Story 1.4) in a
/// live Spring Boot environment. It ensures that revoked tokens are strictly
/// rejected by the security filter chain, even if they remain cryptographically
/// valid.
///
/// ### Testing Safeguards:
/// - **Unique Identity Context**: Uses UUID-based emails to prevent "Dirty Context" issues during high-speed test execution.
/// - **Stateful Workflow**: Simulates the full User lifecycle: Register -> Login -> Use -> Logout -> Deny.
@SpringBootTest
@AutoConfigureMockMvc
class LogoutIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    private String validToken;
    private String userId;
    private String testEmail;

    /// Establishes a fresh, authenticated persona before each test case.
    ///
    /// This setup registers a unique identity and performs a login to capture
    /// a valid JWT, ensuring that logout tests are grounded in an active session.
    @BeforeEach
    void setUp() throws Exception {
        // Must delete child records (Tokens) before parent records (Users)
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        // High-Precision isolation: Ensures unique signatures for every test run
        testEmail = "logout-" + UUID.randomUUID() + "@opayque.com";

        // 1. Onboard a fresh identity
        RegisterRequest registerRequest = new RegisterRequest(testEmail, "password123", "Logout Tester");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest)))
                .andExpect(status().isCreated());

        // 2. Authenticate to retrieve a "Key Card" (JWT)
        LoginRequest loginRequest = new LoginRequest(testEmail, "password123");
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode jsonNode = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        validToken = jsonNode.get("token").asText();

        // Force JPA to write to DB before we try to find it
        userRepository.flush();

        userId = userRepository.findByEmail(testEmail)
                .orElseThrow(() -> new AssertionError("Identity ledger synchronization failed"))
                .getId().toString();
    }

    /// Scenario: Token Invalidation Lifecycle.
    ///
    /// Verifies that a JWT correctly permits access *before* logout and is
    /// immediately blocked *after* logout via the Redis blocklist.
    @Test
    @DisplayName("Integration: Logout should invalidate token (Kill-Switch)")
    void shouldInvalidateTokenAfterLogout() throws Exception {
        // Step 1: Confirm Legitimate Access
        mockMvc.perform(get("/api/v1/users/" + userId)
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk());

        // Step 2: Trigger the Kill Switch
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk());

        // Step 3: Verify access is now denied (401 Unauthorized)
        mockMvc.perform(get("/api/v1/users/" + userId)
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isUnauthorized());
    }

    /// Validates that attempting to use a revoked token for a secondary logout
    /// is rejected, maintaining the integrity of our revocation logic.
    @Test
    @DisplayName("Integration: Double Logout should fail (Token already invalid)")
    void shouldRejectDoubleLogout() throws Exception {
        // 1. Initial Revocation
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isOk());

        // 2. Subsequent attempt with blacklisted signature
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + validToken))
                .andExpect(status().isUnauthorized());
    }

    /// Ensures that the logout endpoint follows strict security protocols,
    /// rejecting requests that lack proper authentication.
    @Test
    @DisplayName("Integration: Logout without token should be Unauthorized")
    void shouldRejectLogoutWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
    }

    /// Verifies that the security gate rejects malformed headers (non-Bearer),
    /// preventing "Mixed Auth" attacks.
    @Test
    @DisplayName("Integration: Logout should reject malformed Authorization header")
    void shouldRejectLogoutWithMalformedHeader() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Basic some-invalid-credentials"))
                .andExpect(status().isUnauthorized());
    }
}