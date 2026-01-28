package com.opayque.api.identity.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/// Epic 1: Identity & Access Management - JWT Security Audit.
///
/// This test suite validates the cryptographic integrity and claim management
/// of the stateless authentication service.
/// It ensures that the oPayque API can securely issue tokens and detect unauthorized
/// tampering or expired credentials.
///
/// Testing Strategy: Pure Unit Testing (No Spring Context) for sub-millisecond
/// execution within CI/CD pipelines.
@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    private JwtService jwtService;

    /// Requirement: 256-bit entropy for HS256 algorithm safety.
    private final String SECRET = "bank-grade-secret-key-at-least-32-chars-long";

    /// Standard access token expiration: 1 hour (3,600,000 ms).
    private final long EXPIRATION = 3600000;

    /// Refresh Token expiration: 7 days (604,800,000 ms).
    private final long REFRESH_EXPIRATION = 604800000;

    /// Initializes the service manually before each test case.
    /// Injects specific temporal and cryptographic properties for pure unit testing
    /// isolation.
    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, EXPIRATION, REFRESH_EXPIRATION);
    }

    /// Verifies that the token generation logic correctly encodes identity claims.
    ///
    /// Confirms that Subject (email) and Role claims are correctly embedded and
    /// recoverable from the signed JWT.
    @Test
    @DisplayName("Unit: Verify token contains correct email and role claims")
    void shouldGenerateTokenWithCorrectClaims() {
        String email = "pro@opayque.com";
        String role = "ROLE_CUSTOMER";

        String token = jwtService.generateToken(email, role);

        assertNotNull(token);
        assertEquals(email, jwtService.extractEmail(token));
        assertEquals(role, jwtService.extractRole(token));
    }

    /// Validates cryptographic signature enforcement.
    ///
    /// Ensures that the verifier correctly identifies and rejects tokens that have
    /// undergone manual bit-flipping or tampering in the signature segment
    ///.
    @Test
    @DisplayName("Unit: Ensure token is signed with our secret key")
    void shouldGenerateValidSignature() {
        String token = jwtService.generateToken("dev@opayque.com", "ROLE_ADMIN");

        assertTrue(jwtService.isTokenValid(token));

        // Manual tamper: Corrupt the cryptographic signature (3rd segment of JWT)
        String[] parts = token.split("\\.");
        String tamperedSignature = parts[2].substring(0, parts[2].length() - 1) +
                (parts[2].endsWith("A") ? "B" : "A");
        String tamperedToken = parts[0] + "." + parts[1] + "." + tamperedSignature;

        assertFalse(jwtService.isTokenValid(tamperedToken));
    }

    /// Validates extraction of the primary identity string (email).
    @Test
    @DisplayName("Unit: Verify extraction of email (username) from token")
    void shouldExtractUsername() {
        String expectedEmail = "tester@opayque.com";
        String token = jwtService.generateToken(expectedEmail, "ROLE_USER");

        String actualEmail = jwtService.extractEmail(token);

        assertEquals(expectedEmail, actualEmail);
    }

    /// Verifies temporal gatekeeping and expiration logic.
    ///
    /// Confirms that tokens exceeding their configured TTL (Time-To-Live) are
    /// rejected by the cryptographic verifier.
    @Test
    @DisplayName("Unit: Should reject an expired token")
    void shouldRejectExpiredToken() throws InterruptedException {
        // Arrange: Instantiate a temporary service with 1ms TTL
        JwtService shortLivedService = new JwtService(SECRET, 1, REFRESH_EXPIRATION);
        String token = shortLivedService.generateToken("old@opayque.com", "ROLE_USER");

        // Act: Induce temporal breach via sleep
        Thread.sleep(10);

        // Assert: Cryptographic validation must fail
        assertFalse(shortLivedService.isTokenValid(token));
    }

    // --- Story 1.6: Opaque Token Verification ---

    /// Verifies that Refresh Tokens utilize an Opaque structure.
    ///
    /// Confirms that generated refresh tokens are high-entropy, random strings and
    /// strictly do not follow the RFC 7519 JWT structure.
    @Test
    @DisplayName("Unit: Refresh Token must be Opaque (Not a JWT)")
    void generateRefreshToken_ShouldReturnOpaqueString() {
        String refreshToken = jwtService.generateRefreshToken();

        assertNotNull(refreshToken);
        assertTrue(refreshToken.length() > 20, "Refresh token should be high entropy (long)");

        // Integrity Check: Opaque tokens must not contain the '.' delimiter
        // characteristic of JWT Header.Payload.Signature structures.
        long dots = refreshToken.chars().filter(ch -> ch == '.').count();
        assertNotEquals(2, dots, "Refresh Token MUST NOT be a JWT structure");
    }
}