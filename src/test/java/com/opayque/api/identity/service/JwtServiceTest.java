package com.opayque.api.identity.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/// **Epic 1: Identity & Access Management — JWT Security Audit**.
///
/// This test suite validates the cryptographic integrity and claim management of the stateless authentication service.
/// It ensures that the **oPayque API** can securely issue tokens and detect unauthorized tampering or expired credentials.
///
/// **Testing Strategy:**
/// * **Pure Unit Testing:** No Spring Context initialization to ensure sub-millisecond execution within CI/CD pipelines.
/// * **Isolation:** Focuses strictly on the [JwtService] business logic and cryptographic signing.
/// * **Verification:** Proof of "Opaque" security principle by ensuring tokens are non-negotiable once signed.
@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    private JwtService jwtService;

    /// **Cryptographic Requirement:** 256-bit entropy for HS256 algorithm safety.
    private final String SECRET = "bank-grade-secret-key-at-least-32-chars-long";

    /// **Temporal Policy:** Standard access token expiration set to 1 hour (3,600,000 ms).
    private final long EXPIRATION = 3600000;

    /// **Persistence Policy:** Refresh Token expiration set to 7 days (604,800,000 ms).
    private final long REFRESH_EXPIRATION = 604800000;

    /// Initializes the service manually before each test case.
    /// Injects specific temporal and cryptographic properties to maintain pure unit testing isolation.
    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, EXPIRATION, REFRESH_EXPIRATION);
    }

    /// Verifies that the token generation logic correctly encodes identity claims.
    ///
    /// This test confirms that:
    /// * **Subject:** The user email is correctly embedded.
    /// * **RBAC:** Roles are correctly assigned within the payload.
    /// * **Recovery:** Claims are fully recoverable from the signed JWT.
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

    /// Validates cryptographic signature enforcement against adversarial tampering.
    ///
    /// **Scenario:** Ensures the verifier rejects tokens that have undergone manual bit-flipping in the signature segment.
    /// **Technical Fix:** Specifically targets the middle of the signature to bypass Base64 padding flakiness.
    @Test
    @DisplayName("Unit: Ensure token is signed with our secret key")
    void shouldGenerateValidSignature() {
        String token = jwtService.generateToken("dev@opayque.com", "ROLE_ADMIN");

        assertTrue(jwtService.isTokenValid(token));

        String[] parts = token.split("\\.");
        String signature = parts[2];

        // Modification of the middle character to guarantee an invalid byte array/checksum
        char originalChar = signature.charAt(10);
        char tamperedChar = (originalChar == 'A') ? 'B' : 'A';

        String tamperedSignature = signature.substring(0, 10) + tamperedChar + signature.substring(11);
        String tamperedToken = parts[0] + "." + parts[1] + "." + tamperedSignature;

        assertFalse(jwtService.isTokenValid(tamperedToken), "Tampered signature must be invalid");
    }

    /// Validates the reliable extraction of the primary identity string (email/username).
    @Test
    @DisplayName("Unit: Verify extraction of email (username) from token")
    void shouldExtractUsername() {
        String expectedEmail = "tester@opayque.com";
        String token = jwtService.generateToken(expectedEmail, "ROLE_USER");

        String actualEmail = jwtService.extractEmail(token);

        assertEquals(expectedEmail, actualEmail);
    }

    /// Verifies temporal gatekeeping and token revocation logic.
    ///
    /// Confirms that tokens exceeding their configured Time-To-Live (TTL) are
    /// rejected by the cryptographic verifier, preventing replay attacks.
    @Test
    @DisplayName("Unit: Should reject an expired token")
    void shouldRejectExpiredToken() throws InterruptedException {
        // Arrange: Instantiate a short-lived service with 1ms TTL
        JwtService shortLivedService = new JwtService(SECRET, 1, REFRESH_EXPIRATION);
        String token = shortLivedService.generateToken("old@opayque.com", "ROLE_USER");

        // Act: Induce temporal breach
        Thread.sleep(10);

        // Assert: Verifier must identify token as expired/invalid
        assertFalse(shortLivedService.isTokenValid(token));
    }

    /// **Story 1.6: Opaque Token Verification**.
    ///
    /// Verifies that Refresh Tokens utilize an **Opaque** structure rather than a JWT structure.
    /// High-entropy random strings are used for session persistence to reduce the attack surface.
    ///
    /// **Security Assertion:**
    /// * Refresh Tokens MUST NOT contain the `.` delimiter characteristic of RFC 7519.
    @Test
    @DisplayName("Unit: Refresh Token must be Opaque (Not a JWT)")
    void generateRefreshToken_ShouldReturnOpaqueString() {
        String refreshToken = jwtService.generateRefreshToken();

        assertNotNull(refreshToken);
        assertTrue(refreshToken.length() > 20, "Refresh token should be high entropy (long)");

        // Integrity Check: Characteristics of Opaque tokens vs. JSON Web Tokens
        long dots = refreshToken.chars().filter(ch -> ch == '.').count();
        assertNotEquals(2, dots, "Refresh Token MUST NOT be a JWT structure");
    }
}