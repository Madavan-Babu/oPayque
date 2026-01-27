package com.opayque.api.infrastructure.util;

import com.opayque.api.identity.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/// Epic 1: Identity & Access Management - Infrastructure Utility
///
/// The **Identity Bridge** for the oPayque ecosystem.
/// This utility provides a centralized, high-integrity gateway to extract the
/// authenticated user's unique identifier from the Spring Security context.
///
/// ### Design Principles:
/// - **Encapsulation**: The ONLY class authorized to touch {@link SecurityContextHolder} in the business layer.
/// - **Fail-Fast**: Throws exceptions immediately if a security context is invalid,
///   preventing "Anonymous" threads from entering financial logic.
/// - **Thread Safety**: Relies on thread-local context management established by the filter chain.
@Slf4j
public final class SecurityUtil {

    /// Private constructor to enforce static usage and prevent instantiation.
    /// @throws UnsupportedOperationException if an attempt is made to instantiate this utility class.
    private SecurityUtil() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /// Extracts the unique `UUID` of the currently authenticated user.
    ///
    /// This method is the foundation of our BOLA defense. It ensures that the
    /// requester's identity is cryptographically grounded before returning their
    /// primary key.
    ///
    /// @return The `UUID` of the logged-in user.
    /// @throws IllegalStateException if the context is empty, anonymous, or contains an invalid principal type.
    public static UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 1. Guard Rail: Validate the existence and authenticity of the context
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            log.error("Security Breach Attempt: Access denied to a protected resource due to invalid context.");
            throw new IllegalStateException("No authenticated user found in Security Context");
        }

        Object principal = authentication.getPrincipal();

        // 2. Principal Verification: Confirm the context contains our domain User entity
        if (principal instanceof User user) {
            return user.getId();
        }

        // 3. Type Safety: Log critical mismatch for audit logs
        log.error("Security Integrity Failure: Principal is [{}] but expected oPayque User entity", principal.getClass());
        throw new IllegalStateException("Principal is not of the expected User type");
    }

    /// Safely clears the current security context.
    /// Used during logout to prevent thread-local leakage.
    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}