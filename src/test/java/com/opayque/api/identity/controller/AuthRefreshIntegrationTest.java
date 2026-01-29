package com.opayque.api.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.identity.dto.LoginRequest;
import com.opayque.api.identity.dto.LoginResponse;
import com.opayque.api.identity.dto.RefreshTokenRequest;
import com.opayque.api.identity.entity.RefreshToken;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.RefreshTokenRepository;
import com.opayque.api.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// Epic 1: Identity & Access Management - Token Lifecycle Integration Testing.
///
/// This suite executes comprehensive end-to-end testing of the session refresh and
/// token rotation mechanisms (Story 1.6). It validates the persistence-backed
/// "Opaque Token" architecture and ensures strict compliance with security guardrails.
///
/// Scenarios covered:
/// - Success path for token exchange and cryptographic rotation.
/// - Prevention of token reuse (anti-replay).
/// - Enforcement of the Single Active Session (SAS) 1:1 policy.
/// - Temporal validation (expiration) and integrity checks.
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthRefreshIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private User testUser;

    /// Initializes the testing environment before each execution.
    /// Purges existing ledger entries to maintain isolation and establishes a
    /// baseline user identity.
    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();

        testUser = User.builder()
                .email("refresh@opayque.com")
                .password(passwordEncoder.encode("SecurePass123!"))
                .fullName("Refresh Tester")
                .role(Role.CUSTOMER)
                .build();
        userRepository.save(testUser);
    }

    /// Scenario A: Happy Path - Token Exchange & Rotation logic.
    ///
    /// Verifies that a valid refresh token can be exchanged for new credentials
    /// and that the system correctly implements rotation by issuing a different
    /// opaque string.
    @Test
    @DisplayName("Scenario A: Happy Path - Should exchange valid refresh token for new credentials")
    void shouldRotateTokensSuccessfully() throws Exception {
        // 1. Authenticate to retrieve the initial dual token pair.
        String initialRefreshToken = loginAndGetRefreshToken();

        // 2. Temporal shift to ensure distinct generation timestamps.
        Thread.sleep(1000);

        // 3. Execution of the refresh request.
        RefreshTokenRequest refreshRequest = new RefreshTokenRequest(initialRefreshToken);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(refreshRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.refreshToken").exists())
                .andReturn();

        LoginResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);

        // 4. Verification: The new token must be cryptographically distinct from the original.
        assertNotEquals(initialRefreshToken, response.refreshToken(), "Refresh Token MUST rotate");
    }

    /// Scenario B: Anti-Replay Guard - Blocking Token Reuse.
    ///
    /// Ensures that once a token has been rotated, the previous iteration is
    /// immediately invalidated and rejected upon subsequent use.
    @Test
    @DisplayName("Scenario B: Rotation Check - Reusing an old token should be Unauthorized")
    void shouldBlockReusedToken() throws Exception {
        // 1. Establish initial session (Token A).
        String tokenA = loginAndGetRefreshToken();

        // 2. Perform rotation to obtain Token B (invalidating Token A).
        RefreshTokenRequest requestA = new RefreshTokenRequest(tokenA);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestA)))
                .andExpect(status().isOk());

        // 3. Attempt unauthorized reuse of the superseded Token A.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestA)))
                .andExpect(status().isUnauthorized());
    }

    /// Scenario C: SAS Enforcer - Session Displacement.
    ///
    /// Validates the 1:1 session policy where a new authentication event
    /// on "Device B" automatically displaces and invalidates the session
    /// on "Device A".
    @Test
    @DisplayName("Scenario C: The 1:1 Enforcer - Device B login should invalidate Device A")
    void shouldEnforceSingleSession() throws Exception {
        // 1. Initial authentication session (Device A).
        String tokenDeviceA = loginAndGetRefreshToken();

        // 2. Secondary authentication session (Device B) displacing the first.
        loginAndGetRefreshToken();

        // 3. Attempt to utilize the displaced session (Device A), expecting rejection.
        RefreshTokenRequest requestA = new RefreshTokenRequest(tokenDeviceA);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestA)))
                .andExpect(status().isUnauthorized());
    }

    /// Scenario D: Zombie Check - Temporal Validation.
    ///
    /// Confirms that tokens that have surpassed their configured TTL (Time To Live)
    /// are rejected as "dead on arrival" during the rotation attempt.
    @Test
    @DisplayName("Scenario D: Zombie Check - Expired tokens must be dead on arrival")
    void shouldRejectExpiredToken() throws Exception {
        // 1. Inject a manually expired session into the ledger.
        String zombieToken = "zombie-token-value";
        RefreshToken expiredEntity = RefreshToken.builder()
                .token(zombieToken)
                .user(testUser)
                .expiryDate(Instant.now().minusSeconds(1)) // Temporal boundary breached
                .revoked(false)
                .build();
        refreshTokenRepository.save(expiredEntity);

        // 2. Attempt unauthorized exchange of the expired session.
        RefreshTokenRequest request = new RefreshTokenRequest(zombieToken);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    /// Scenario E: Integrity Check - Tamper Detection.
    ///
    /// Validates that arbitrary modifications to the opaque token string
    /// result in a lookup failure and subsequent rejection.
    @Test
    @DisplayName("Scenario E: Integrity Check - Tampered tokens should fail")
    void shouldRejectTamperedToken() throws Exception {
        String validToken = loginAndGetRefreshToken();
        String tamperedToken = validToken + "hack";

        RefreshTokenRequest request = new RefreshTokenRequest(tamperedToken);
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    /// Internal helper: Executes a full login handshake and extracts the refresh token.
    private String loginAndGetRefreshToken() throws Exception {
        LoginRequest loginRequest = new LoginRequest("refresh@opayque.com", "SecurePass123!");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        LoginResponse response = objectMapper.readValue(result.getResponse().getContentAsString(), LoginResponse.class);
        return response.refreshToken();
    }
}