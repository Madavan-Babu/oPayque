package com.opayque.api.identity.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.auth0.jwt.interfaces.JWTVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * Epic 1: Identity & Access Management - Stateless Trust Layer
 * * Industrial-grade service for JWT generation, parsing, and validation.
 * Uses Auth0 java-jwt for RFC 7519 compliance, ensuring that all issued tokens
 * are cryptographically sound and tamper-evident.
 * * Security Features:
 * - HMAC256 signature algorithm using a bank-grade secret key.
 * - Configurable temporal expiration to mitigate replay attacks.
 * - Issuer verification to ensure tokens are originated from the oPayque ecosystem.
 */
@Slf4j
@Service
public class JwtService {

    private final String secretKey;
    private final long jwtExpiration;
    private final Algorithm algorithm;
    private final JWTVerifier verifier;

    private static final String CLAIM_ROLE = "role";
    private static final String ISSUER = "opayque-identity-service";

    /**
     * Initializes the service with security properties injected from the environment.
     * * @param secretKey The 256-bit entropy secret used for HMAC signing.
     * @param jwtExpiration The time-to-live (TTL) for issued tokens in milliseconds.
     */
    public JwtService(
            @Value("${application.security.jwt.secret-key}") String secretKey,
            @Value("${application.security.jwt.expiration}") long jwtExpiration
    ) {
        this.secretKey = secretKey;
        this.jwtExpiration = jwtExpiration;
        this.algorithm = Algorithm.HMAC256(secretKey);
        this.verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build();
    }

    /**
     * Generates a stateless JWT containing user identity and role claims.
     * * @param email The user's unique identifier (Subject).
     * @param role The assigned security role for RBAC enforcement.
     * @return A signed, base64-encoded JWT string.
     */
    public String generateToken(String email, String role) {
        log.info("Generating JWT for user: {}", email);
        return JWT.create()
                .withSubject(email)
                .withClaim(CLAIM_ROLE, role)
                .withIssuedAt(new Date())
                .withExpiresAt(new Date(System.currentTimeMillis() + jwtExpiration))
                .withIssuer(ISSUER)
                .sign(algorithm);
    }

    /**
     * Extracts the user email (Subject) from the provided token.
     * * @param token The signed JWT.
     * @return The subject email address.
     */
    public String extractEmail(String token) {
        return decodeToken(token).getSubject();
    }

    public Date extractExpiration(String token) {
        return decodeToken(token).getExpiresAt();
    }

    /**
     * Extracts the security role claim from the provided token.
     * * @param token The signed JWT.
     * @return The role string (e.g., ROLE_CUSTOMER).
     */
    public String extractRole(String token) {
        return decodeToken(token).getClaim(CLAIM_ROLE).asString();
    }

    /**
     * Performs a full cryptographic verification of the token.
     * * This includes checking the signature against our secret, ensuring the token
     * has not expired, and verifying the issuer.
     *
     * @param token The signed JWT to validate.
     * @return true if the token is valid and active; false otherwise.
     */
    public boolean isTokenValid(String token) {
        try {
            verifier.verify(token);
            return true;
        } catch (JWTVerificationException e) {
            log.error("JWT Verification failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Decodes the token using the configured verifier.
     * Internal utility to reduce duplication across extraction methods.
     */
    private DecodedJWT decodeToken(String token) {
        return verifier.verify(token);
    }
}