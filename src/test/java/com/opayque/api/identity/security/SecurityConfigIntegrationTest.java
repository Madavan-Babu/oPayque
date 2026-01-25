package com.opayque.api.identity.security;

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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/// Epic 1: Identity & Access Management - Security Configuration Integration Testing
///
/// This suite validates the overall security perimeter of the oPayque API.
/// It ensures that the {@link SecurityConfig} correctly permits public access
/// to authentication gateways while enforcing strict JWT validation for all
/// protected financial resources.
///
/// ### Verification Focus:
/// - **Public Access**: Confirming /login and /register are reachable without credentials.
/// - **Authorization Hardening**: Ensuring non-authenticated requests to sensitive paths result in a 403 Forbidden.
/// - **Bearer Token Integration**: Validating that the filter chain correctly parses and trusts issued JWTs.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    /// Clears the identity ledger and seeds a fresh developer identity before each test case.
    ///
    /// This maintains a "Reliability-First" testing baseline and ensures that JWT generation
    /// tests are grounded in a valid, persisted user record.
    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
        userRepository.save(User.builder()
                .email("dev@opayque.com")
                .password(passwordEncoder.encode("password"))
                .fullName("Dev User")
                .role(Role.CUSTOMER)
                .build());
    }

    /// Verifies that onboarding endpoints are not protected by the security filter chain.
    ///
    /// We specifically check that the response is NOT a 403 Forbidden, indicating that
    /// the request successfully bypassed the security gate.
    @Test
    @DisplayName("Security: Public endpoints should be accessible without token")
    void shouldAllowAccessToPublicEndpoints() throws Exception {
        // Refactored: Using Hamcrest not() to assert the status is not Forbidden.
        // We expect business logic or validation errors (4xx) rather than security blocks (403).

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(not(HttpStatus.FORBIDDEN.value())));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().is(not(HttpStatus.FORBIDDEN.value())));
    }

    /// Validates that the "Opaque" security model defaults to a "Deny-All" policy
    /// for internal API paths when no valid credential is provided.
    @Test
    @DisplayName("Security: Protected endpoints should return 403 when no token is present")
    void shouldBlockAccessToProtectedEndpoints() throws Exception {
        // Attempting to access a hypothetical protected endpoint without a Bearer token
        mockMvc.perform(get("/api/v1/demo/me"))
                .andExpect(status().isForbidden());
    }

    /// Confirms that the end-to-end security flow allows access once a valid
    /// stateless JWT is presented in the Authorization header.
    @Test
    @DisplayName("Security: Protected endpoints should return 200 when valid JWT is provided")
    void shouldAllowAccessWithValidToken() throws Exception {
        // Arrange: Issue a cryptographically signed token for a test identity
        String token = jwtService.generateToken("dev@opayque.com", "ROLE_CUSTOMER");

        // Act & Assert: Accessing the protected endpoint with the Bearer scheme
        // Note: Requires a functional controller mapping for /api/v1/demo/me to return 200 OK.
        mockMvc.perform(get("/api/v1/demo/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}