package com.opayque.api.identity.controller;

import jakarta.transaction.Transactional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// Epic 1: Identity & Access Management - Full-Stack Integration Testing.
///
/// This suite executes comprehensive integration testing for the AuthController endpoint.
/// It validates the end-to-end registration lifecycle, including:
/// 1. Security filter chain interception.
/// 2. Request payload validation.
/// 3. Service-layer business logic.
/// 4. JPA persistence and transaction integrity.
///
/// The @Transactional annotation ensures that the database ledger is rolled back
/// after each execution to preserve environment purity.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /// Verifies successful registration workflow for valid payloads.
    ///
    /// Validates that the system returns an HTTP 201 Created status and
    /// properly applies PII masking (Story 1.5) to the response egress.
    @Test
    @DisplayName("TDD: Should successfully register when valid data is provided")
    void shouldRegisterNewUser() throws Exception {
        String userJson = """
            {
                "email": "madavan@opayque.com",
                "password": "SecurePassword123!",
                "fullName": "Madavan Portfolio"
            }
            """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isCreated())
                // Asserting Story 1.5 Masking logic: "Madavan Portfolio" -> "M***"
                .andExpect(jsonPath("$.fullName").value("M***"))
                // Asserting Story 1.5 Masking logic: "madavan@opayque.com" -> "m***@opayque.com"
                .andExpect(jsonPath("$.email").value("m***@opayque.com"));
    }

    /// Validates identity uniqueness constraints (Identity Guardrail).
    ///
    /// Confirms that duplicate email registration attempts trigger an
    /// HTTP 409 Conflict response via the centralized exception handler.
    @Test
    @DisplayName("TDD: Should fail to register if email already exists")
    void shouldFailWhenEmailExists() throws Exception {
        String userJson = """
            {
                "email": "madavan@opayque.com",
                "password": "SecurePassword123!",
                "fullName": "Madavan Portfolio"
            }
            """;

        // Step 1: Establish initial identity in the ledger
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isCreated());

        // Step 2: Attempt duplicate registration of the same identity
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isConflict());
    }

    /// Security Audit: Validates the "Opaque" Data Standard.
    ///
    /// Ensures that critical security artifacts, specifically password hashes,
    /// are never exposed in the API response body.
    @Test
    @DisplayName("Security: Should not return password in response")
    void shouldNotReturnPasswordInResponse() throws Exception {
        String userJson = """
            {
                "email": "security@opayque.com",
                "password": "SecurePassword123!",
                "fullName": "Security Audit"
            }
            """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    /// Input Validation: Rejection of malformed payloads.
    ///
    /// Confirms that invalid email formats or insufficient password strength
    /// are rejected with an HTTP 400 Bad Request.
    @Test
    @DisplayName("Validation: Should fail to register with invalid email")
    void shouldFailWithInvalidEmail() throws Exception {
        String userJson = """
            {
                "email": "not-an-email",
                "password": "123",
                "fullName": ""
            }
            """;

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isBadRequest());
    }

    /// Cross-Cutting Logic: Validation Error Mapping.
    ///
    /// Ensures that the GlobalExceptionHandler correctly aggregates and
    /// structures multiple validation failures into a coherent JSON error map.
    @Test
    @DisplayName("Validation: Should trigger all error paths in GlobalExceptionHandler")
    void shouldTriggerFullValidationHandler() throws Exception {
        String emptyJson = "{}";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(emptyJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.email").exists())
                .andExpect(jsonPath("$.password").exists())
                .andExpect(jsonPath("$.fullName").exists());
    }

    /// Resilience Audit: Catch-all Exception Handling.
    ///
    /// Verifies that unexpected system errors or malformed request syntax
    /// result in a controlled HTTP 500 Internal Server Error.
    @Test
    @DisplayName("Chaos: Should trigger General Exception handler on unexpected failure")
    void shouldHandleUnexpectedExceptions() throws Exception {
        // Inducing a JSON parsing failure via malformed content
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("!!Invalid JSON!!"))
                .andExpect(status().isInternalServerError());
    }
}