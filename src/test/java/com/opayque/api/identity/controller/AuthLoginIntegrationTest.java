package com.opayque.api.identity.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.identity.dto.LoginRequest;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// Epic 1: Identity & Access Management - Authentication Integration Testing
///
/// This suite validates the full authentication lifecycle of the oPayque API.
/// It verifies the integration between REST controllers, the internal security filter chain,
/// and the PostgreSQL database.
///
/// ### Test Objectives:
/// - **Credential Validation**: Ensuring correctly hashed passwords allow system access.
/// - **JWT Issuance**: Confirming that successful logins return a valid 'Bearer' token.
/// - **Security Hardening**: Verifying that unauthorized attempts are rejected with 401 status codes.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthLoginIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /// Clears the identity ledger and seeds a fresh test user before each test case.
    ///
    /// This ensures environment isolation and a "Reliability-First" baseline for
    /// every integration attempt.
    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        // Seed an authentic identity with a BCrypt-hashed password
        User user = User.builder()
                .email("test@opayque.com")
                .password(passwordEncoder.encode("SecurePass123!"))
                .fullName("Test User")
                .role(Role.CUSTOMER)
                .build();
        userRepository.save(user);
    }

    /// Verifies the "Happy Path" where valid credentials result in an HTTP 200
    /// and a stateless JWT.
    @Test
    @DisplayName("Integration: Successful login returns 200 and JWT")
    void shouldReturn200AndTokenOnValidLogin() throws Exception {
        LoginRequest request = new LoginRequest("test@opayque.com", "SecurePass123!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").exists())
                .andExpect(jsonPath("$.type").value("Bearer"));
    }

    /// Validates that an incorrect password prevents token issuance and
    /// triggers a standard 401 Unauthorized response.
    @Test
    @DisplayName("Integration: Bad password returns 401 Unauthorized")
    void shouldReturn401OnBadPassword() throws Exception {
        LoginRequest request = new LoginRequest("test@opayque.com", "WrongPass");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    /// Ensures that the system does not leak identity presence and returns a
    /// uniform 401 status for non-existent users.
    @Test
    @DisplayName("Integration: Non-existent user returns 401 Unauthorized")
    void shouldReturn401OnNonExistentUser() throws Exception {
        LoginRequest request = new LoginRequest("ghost@opayque.com", "anyPass");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}