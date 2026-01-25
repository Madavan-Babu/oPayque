package com.opayque.api.identity.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/// Epic 1: Identity & Access Management - JWT Security Audit
/// * This test suite validates the cryptographic integrity and claim management
/// of our stateless authentication service. It ensures that the oPayque API
/// can securely issue tokens and detect unauthorized tampering or expired
/// credentials.
/// * Security Standards:
/// - HMAC-SHA for signature verification.
/// - Temporal validation (Expiration checks).
/// - Claim-based authorization (Email and Role).
@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    private JwtService jwtService;

    /** Requirement: 256-bit entropy for HS256 algorithm safety. */
    private final String SECRET = "bank-grade-secret-key-at-least-32-chars-long";

    /** Standard expiration set to 1 hour (36,00,000 ms). */
    private final long EXPIRATION = 3600000;

    /**
     * Initializes the service manually for pure unit testing.
     * This avoids Spring context overhead, ensuring ultra-fast execution in the
     * CI/CD pipeline.
     */
    @BeforeEach
     void setUp() {
        jwtService = new JwtService(SECRET, EXPIRATION);
    }

    /**
     * Verifies that the token generation logic correctly encodes identity claims.
     * This is vital for maintaining the "Opaque" security model where the
     * token carries all necessary context.
     */
    @Test
    @DisplayName("Unit: Verify token contains correct email and role claims")
    void shouldGenerateTokenWithCorrectClaims() {
        // Arrange
        String email = "pro@opayque.com";
        String role = "ROLE_CUSTOMER";

        // Act
        String token = jwtService.generateToken(email, role);

        // Assert
        assertNotNull(token);
        assertEquals(email, jwtService.extractEmail(token));
        assertEquals(role, jwtService.extractRole(token));
    }

    /**
     * Validates cryptographic signature enforcement.
     * Any alteration to the JWT signature segment must result in an immediate
     * rejection to prevent session hijacking.
     */
    @Test
    @DisplayName("Unit: Ensure token is signed with our secret key")
    void shouldGenerateValidSignature() {
        // Arrange
        String token = jwtService.generateToken("dev@opayque.com", "ROLE_ADMIN");

        // Act & Assert: Valid token should pass
        assertTrue(jwtService.isTokenValid(token));

        // Manual tamper: Corrupt the signature (3rd part of JWT)
        String[] parts = token.split("\\.");
        String tamperedSignature = parts[2].substring(0, parts[2].length() - 1) +
                (parts[2].endsWith("A") ? "B" : "A");
        String tamperedToken = parts[0] + "." + parts[1] + "." + tamperedSignature;

        // Assert: Signature verification must fail
        assertFalse(jwtService.isTokenValid(tamperedToken));
    }

    /**
     * Ensures that the username (email) can be accurately extracted from
     * the subject claim of the JWT.
     */
    @Test
    @DisplayName("Unit: Verify extraction of email (username) from token")
    void shouldExtractUsername() {
        String expectedEmail = "tester@opayque.com";
        String token = jwtService.generateToken(expectedEmail, "ROLE_USER");

        String actualEmail = jwtService.extractEmail(token);

        assertEquals(expectedEmail, actualEmail);
    }

    /**
     * Verifies the temporal "Gatekeeper" logic.
     * Expired tokens must be invalidated to protect against replay attacks
     *.
     */
    @Test
    @DisplayName("Unit: Should reject an expired token")
    void shouldRejectExpiredToken() throws InterruptedException {
        // Arrange: Create a service with 1ms TTL for testing
        JwtService shortLivedService = new JwtService(SECRET, 1);
        String token = shortLivedService.generateToken("old@opayque.com", "ROLE_USER");

        // Act: Force expiration through delay
        Thread.sleep(10);

        // Assert
        assertFalse(shortLivedService.isTokenValid(token));
    }
}