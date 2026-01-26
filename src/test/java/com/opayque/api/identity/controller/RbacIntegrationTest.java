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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// Epic 1: Identity & Access Management - RBAC Integration Tests
///
/// This suite verifies the **"Role Gates"** of the oPayque application.
/// It ensures that possessing a cryptographically valid token is insufficient for high-privilege
/// operations; the requester must also hold the specific rank (ADMIN vs. CUSTOMER) required
/// by the resource.
///
/// ### Verification Focus:
/// - **Privilege Escalation Prevention**: Ensuring `CUSTOMER` accounts cannot access `ADMIN` routes.
/// - **Stateless Role Mapping**: Confirming roles embedded in the JWT are correctly parsed by the filter chain.
/// - **Authentication vs. Authorization**: Distinguishing between missing credentials (401) and insufficient permissions (403).
@SpringBootTest
@AutoConfigureMockMvc
class RbacIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private String customerToken;

    /// Clears the identity ledger and seeds fresh test principals with varying roles.
    ///
    /// This setup generates high-precision JWTs grounded in the actual database state,
    /// simulating a real-world multi-tier authentication flow.
    @BeforeEach
    void setUp() {
        // 1. Clear the deck to prevent test pollution and ensure idempotent runs
        userRepository.deleteAll();

        // 2. Create and Tokenize an ADMIN identity
        User admin = User.builder()
                .email("admin@opayque.com")
                .password(passwordEncoder.encode("adminPass"))
                .fullName("Chief Admin")
                .role(Role.ADMIN)
                .build();
        userRepository.save(admin);
        adminToken = jwtService.generateToken(admin.getEmail(), admin.getRole().name());

        // 3. Create and Tokenize a CUSTOMER identity
        User customer = User.builder()
                .email("customer@opayque.com")
                .password(passwordEncoder.encode("userPass"))
                .fullName("Regular Joe")
                .role(Role.CUSTOMER)
                .build();
        userRepository.save(customer);
        customerToken = jwtService.generateToken(customer.getEmail(), customer.getRole().name());
    }

    /// Scenario 1: High-Privilege Success.
    ///
    /// Verifies that a valid ADMIN token can successfully bypass the authorization
    /// check for sensitive administrative endpoints.
    @Test
    @DisplayName("Integration: ADMIN should access protected stats endpoint (200 OK)")
    void adminCanAccessSensitiveStats() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }

    /// Scenario 2: Low-Privilege Block (The "Hard Stop").
    ///
    /// Validates the core security gate; verifying that a valid CUSTOMER token is
    /// explicitly rejected by the Role Manager when attempting to access ADMIN resources
    ///.
    @Test
    @DisplayName("Integration: CUSTOMER should be forbidden from admin stats (403 Forbidden)")
    void customerIsForbiddenFromStats() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats")
                        .header("Authorization", "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    /// Scenario 3: Anonymous Block.
    ///
    /// Verifies that requests with no token are treated as "Unknown" (401),
    /// distinct from requests with tokens that have "Insufficient Rank" (403).
    @Test
    @DisplayName("Integration: Unauthenticated request should be unauthorized (401 Unauthorized)")
    void unauthenticatedIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}