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

/**
 * Epic 1: Identity & Access Management - Integration Testing
 * * This suite performs full-stack integration testing of the {@link AuthController}.
 * It verifies the end-to-end flow of user registration, including security filters,
 * data validation, and database persistence within a transactional test context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional // Ensures test data is rolled back after each execution to maintain environment purity
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Verifies that valid registration payloads result in a 201 Created status and
     * correct response body mapping.
     */
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
                .andExpect(jsonPath("$.fullName").value("Madavan Portfolio"));
    }

    /**
     * Validates the "Identity Guardrail" by ensuring that duplicate emails trigger
     * a 409-Conflict response from the Exception Handler.
     */
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

        // Step 1: Establish an existing identity in the ledger
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isCreated());

        // Step 2: Attempt duplicate registration
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson))
                .andExpect(status().isConflict());
    }

    /**
     * Security Audit: Confirms that the registration response adheres to the
     * "Opaque" standard by not exposing password data.
     */
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

    /**
     * Input Validation: Verifies that malformed payloads (invalid email, short password)
     * are rejected with a 400 Bad Request.
     */
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

    /**
     * Cross-Cutting Logic: Ensures that the GlobalExceptionHandler correctly maps
     * multiple validation failures into a structured JSON error map.
     */
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

    /**
     * Resilience Audit: Ensures that unexpected syntax errors are caught by the
     * general Exception handler to return a 500 Internal Server Error.
     */
    @Test
    @DisplayName("Chaos: Should trigger General Exception handler on unexpected failure")
    void shouldHandleUnexpectedExceptions() throws Exception {
        // Triggering a parse failure via malformed JSON content
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("!!Invalid JSON!!"))
                .andExpect(status().isInternalServerError());
    }
}