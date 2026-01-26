package com.opayque.api.identity.controller;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.identity.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// Epic 1: Identity & Access Management - BOLA Integration Tests
///
/// This suite validates the **"Ownership Gates"** designed to mitigate Broken Object Level Authorization (BOLA) vulnerabilities.
/// It ensures that a valid authentication token does not grant universal access; users are strictly confined to
/// resources (Profiles, Wallets) associated with their own unique identifier.
///
/// ### Security Architecture:
/// - **Target Resource**: User Profile (`/api/v1/users/{id}`).
/// - **Security Principle**: Zero Trust (Verify ownership on every transaction).
/// - **Identity Verification**: Uses {@link JwtService} to generate authentic tokens for various test personas.
@SpringBootTest
@AutoConfigureMockMvc
class BolaIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private User thief; // Persona A: The Attacker
    private User victim; // Persona B: The Target
    private String thiefToken;

    /// Clears the identity ledger and seeds separate user personas to simulate
    /// a multi-tenant environment.
    ///
    /// The "Thief" account is issued a legitimate token, which will be used to
    /// attempt unauthorized access to the "Victim" account.
    @BeforeEach
    void setUp() {
        // Clear the persistence layer to maintain idempotent test results
        userRepository.deleteAll();

        // 1. Initialize and persist the "Thief" (The attacker)
        thief = User.builder()
                .email("thief@opayque.com")
                .password(passwordEncoder.encode("pass"))
                .fullName("Thief Account")
                .role(Role.CUSTOMER)
                .build();
        userRepository.save(thief);
        thiefToken = jwtService.generateToken(thief.getEmail(), thief.getRole().name());

        // 2. Initialize and persist the "Victim" (The target)
        victim = User.builder()
                .email("victim@opayque.com")
                .password(passwordEncoder.encode("pass"))
                .fullName("Victim Account")
                .role(Role.CUSTOMER)
                .build();
        userRepository.save(victim);
    }

    /// Scenario 1: Legitimate Access.
    ///
    /// Verifies the "Happy Path" where the authenticated identity matches the
    /// resource owner ID provided in the URI.
    @Test
    @DisplayName("Integration: User should access their OWN profile (200 OK)")
    void userCanAccessOwnWallet() {
        try {
            mockMvc.perform(get("/api/v1/users/" + thief.getId())
                            .header("Authorization", "Bearer " + thiefToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk());
        } catch (Exception e) {
            throw new RuntimeException("MockMvc execution failed", e);
        }
    }

    /// Scenario 2: BOLA Attack (ID Switching).
    ///
    /// Validates the primary BOLA gate; ensuring that a user possessing a valid
    /// token is denied access when the URI ID belongs to a different user identity.
    @Test
    @DisplayName("Integration: User should be BLOCKED from other profile (403 Forbidden)")
    void userIsBlockedFromOtherWallet() {
        try {
            mockMvc.perform(get("/api/v1/users/" + victim.getId())
                            .header("Authorization", "Bearer " + thiefToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        } catch (Exception e) {
            throw new RuntimeException("MockMvc execution failed", e);
        }
    }

    /// Scenario 3: Missing Resource Hygiene.
    ///
    /// Verifies that requesting a non-existent UUID still triggers a 403 Forbidden,
    /// ensuring that the BOLA check occurs BEFORE any data existence checks to
    /// prevent ID enumeration attacks.
    @Test
    @DisplayName("Integration: Random/Non-existent ID should return 403 (Prevent ID Enumeration)")
    void shouldHandleMissingResource() {
        try {
            mockMvc.perform(get("/api/v1/users/" + UUID.randomUUID())
                            .header("Authorization", "Bearer " + thiefToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        } catch (Exception e) {
            throw new RuntimeException("MockMvc execution failed", e);
        }
    }

    /// Scenario 4: Soft-Delete "Resurrection" Attack.
    ///
    /// Ensures that even if a victim's account is soft-deleted, the security gate
    /// prioritizes authorization (403) over data state, preventing attackers from
    /// gleaning account status metadata.
    @Test
    @DisplayName("Integration: User cannot access soft-deleted resource of another user")
    void shouldHandleSoftDeletedResourceAccess() {
        try {
            // Arrange: Apply soft-delete compliance policy to the target
            victim.setDeletedAt(LocalDateTime.now());
            userRepository.save(victim);

            // Act: Thief attempts to access the deactivated account
            mockMvc.perform(get("/api/v1/users/" + victim.getId())
                            .header("Authorization", "Bearer " + thiefToken)
                            .contentType(MediaType.APPLICATION_JSON))
                    .andExpect(status().isForbidden());
        } catch (Exception e) {
            throw new RuntimeException("MockMvc execution failed", e);
        }
    }
}