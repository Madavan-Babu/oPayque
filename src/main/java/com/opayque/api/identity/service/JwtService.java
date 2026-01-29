package com.opayque.api.identity.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

/// Epic 1: Identity & Access Management - Stateless Trust Layer.
///
/// Industrial-grade service for JWT generation, parsing, and validation.
/// Utilizes Auth0 java-jwt for RFC 7519 compliance, ensuring all issued tokens
/// are cryptographically sound and tamper-evident.
///
/// Security Features:
/// - HMAC256 signature algorithm utilizing a bank-grade secret key.
/// - Configurable temporal expiration to mitigate replay attacks.
/// - Issuer verification to ensure tokens originate from the oPayque ecosystem.
/// - Opaque Refresh Token generation for Session Rotation (Story 1.6).
@Slf4j
@Service
public class JwtService {

    private final String secretKey;
    private final long jwtExpiration;
    private final long refreshExpiration;
    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    private static final String CLAIM_ROLE = "role";
    private static final String ISSUER = "opayque-identity-service";

    /// Initializes the service with security properties injected from the environment.
    /// The HMAC256 algorithm instance is cached for optimal performance.
    public JwtService(
            @Value("${application.security.jwt.secret-key}") String secretKey,
            @Value("${application.security.jwt.expiration}") long jwtExpiration,
            @Value("${application.security.jwt.refresh-token.expiration}") long refreshExpiration
    ) {
        this.secretKey = secretKey;
        this.jwtExpiration = jwtExpiration;
        this.refreshExpiration = refreshExpiration;
        this.algorithm = Algorithm.HMAC256(secretKey);

        // Pre-configure the verifier to enforce Issuer and Signature checks globally.
        this.verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build();
    }

    // --- JWT ACCESS TOKEN LOGIC ---

    /// Generates a signed JWT for the authenticated user.
    ///
    /// @param email The user's unique identifier (Subject).
    /// @param role The user's role (RBAC Claim).
    /// @return A URL-safe JWT string.
    public String generateToken(String email, String role) {
        return JWT.create()
                .withSubject(email)
                .withClaim(CLAIM_ROLE, role)
                .withIssuer(ISSUER)
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + jwtExpiration))
                .sign(algorithm);
    }

    /// Extracts the subject (email) from the verified token.
    ///
    /// @param token The signed JWT.
    /// @return The subject email address.
    public String extractEmail(String token) {
        return decodeToken(token).getSubject();
    }

    /// Extracts the expiration date from the verified token.
    ///
    /// @param token The signed JWT.
    /// @return The expiration Date.
    public Date extractExpiration(String token) {
        return decodeToken(token).getExpiresAt();
    }

    /// Extracts the security role claim from the provided token.
    ///
    /// @param token The signed JWT.
    /// @return The role string (e.g., ROLE_CUSTOMER).
    public String extractRole(String token) {
        return decodeToken(token).getClaim(CLAIM_ROLE).asString();
    }

    /// Performs a full cryptographic verification of the token.
    ///
    /// Checks the signature against the system secret, validates the expiration
    /// window, and verifies the trusted issuer.
    ///
    /// @param token The signed JWT to validate.
    /// @return true if the token is valid and active; false otherwise.
    public boolean isTokenValid(String token) {
        try {
            verifier.verify(token);
            return true;
        } catch (JWTVerificationException e) {
            log.error("JWT Verification failed: {}", e.getMessage());
            return false;
        }
    }

    /// Decodes the token utilizing the pre-configured verifier.
    /// Internal utility to centralize verification and reduce logic duplication.
    private DecodedJWT decodeToken(String token) {
        return verifier.verify(token);
    }

    // --- REFRESH TOKEN LOGIC (Story 1.6 - Opaque) ---

    /// Generates a high-entropy opaque string for user session refresh.
    ///
    /// Unlike a JWT, this token is non-descriptive, containing no PII, and
    /// functions strictly as a random reference key for database lookup.
    ///
    /// @return A Base64-encoded, URL-safe, 64-byte random string.
    public String generateRefreshToken() {
        byte[] randomBytes = new byte[64];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    /// Retrieves the configured temporal lifespan for refresh tokens.
    public long getRefreshExpiration() {
        return refreshExpiration;
    }
}