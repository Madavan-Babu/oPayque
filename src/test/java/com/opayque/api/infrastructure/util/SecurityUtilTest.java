package com.opayque.api.infrastructure.util;

import com.opayque.api.identity.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/// Epic 1: Identity & Access Management - Infrastructure Utility Testing
///
/// This suite validates the cryptographic and logical integrity of the {@link SecurityUtil}.
/// It ensures that the "Identity Bridge" correctly handles the thread-local security context,
/// prevents instantiation, and maintains a strict "Fail-Fast" policy for unauthenticated
/// access attempts.
///
/// ### Verification Focus:
/// - **Identity Extraction**: Confirming valid UUID retrieval from the authenticated principal.
/// - **Context Guarding**: Validating rejections of null, anonymous, or malformed contexts.
/// - **Design Enforcement**: Ensuring the utility pattern is strictly followed via reflection testing.
class SecurityUtilTest {

    /// Resets the SecurityContextHolder before each test to ensure environmental isolation.
    /// This prevents "Cross-Pollination" between test threads.
    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    /// Verifies the "Happy Path" where a valid {@link User} entity exists in the context
    /// and its UUID is successfully extracted.
    @Test
    @DisplayName("Unit: Should accurately extract UUID from SecurityContext")
    void shouldReturnUserId() {
        // Arrange: Populate the context with a valid domain principal
        UUID expectedId = UUID.randomUUID();
        var principal = com.opayque.api.identity.entity.User.builder()
                .id(expectedId)
                .email("test@opayque.com")
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList())
        );

        // Act & Assert
        UUID actualId = SecurityUtil.getCurrentUserId();
        assertEquals(expectedId, actualId);
    }

    /// Validates the safety mechanism that triggers when an unauthenticated thread
    /// attempts to access protected identity logic.
    @Test
    @DisplayName("Unit: Should throw IllegalStateException if Context is Null")
    void shouldThrowWhenContextIsNull() {
        SecurityContextHolder.clearContext();

        IllegalStateException ex = assertThrows(IllegalStateException.class, SecurityUtil::getCurrentUserId);
        assertEquals("No authenticated user found in Security Context", ex.getMessage());
    }

    /// Ensures that the default Spring Security 'anonymousUser' string is not
    /// treated as a valid identity, preventing BOLA vulnerabilities.
    @Test
    @DisplayName("Unit: Should throw IllegalStateException if Principal is 'anonymousUser'")
    void shouldThrowWhenPrincipalIsAnonymous() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("anonymousUser", null, Collections.emptyList())
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, SecurityUtil::getCurrentUserId);
        assertEquals("No authenticated user found in Security Context", ex.getMessage());
    }

    /// Verifies that any principal that does not match our {@link User} entity type
    /// is rejected with a clear type-mismatch error.
    @Test
    @DisplayName("Unit: Should throw IllegalStateException if Principal is wrong type")
    void shouldThrowWhenPrincipalIsWrongType() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("JustAString", "pass", Collections.emptyList())
        );

        IllegalStateException ex = assertThrows(IllegalStateException.class, SecurityUtil::getCurrentUserId);
        assertEquals("Principal is not of the expected User type", ex.getMessage());
    }

    /// Enforces the architectural rule that utility classes must not be instantiated.
    /// Uses reflection to verify that the private constructor is guarded.
    @Test
    @DisplayName("Unit: Private Constructor should throw UnsupportedOperationException")
    void shouldThrowOnInstantiation() throws NoSuchMethodException {
        // Use reflection to bypass visibility for testing purposes
        Constructor<SecurityUtil> constructor = SecurityUtil.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        InvocationTargetException ex = assertThrows(InvocationTargetException.class, constructor::newInstance);

        // Verify that the actual logic in the constructor is what threw the exception
        assertInstanceOf(UnsupportedOperationException.class, ex.getCause(),
                "The utility constructor must throw UnsupportedOperationException to prevent instantiation.");
    }

    /// Validates the check for tokens that may be present but are not flagged
    /// as 'authenticated' by the provider.
    @Test
    @DisplayName("Unit: Should throw IllegalStateException if Authentication is present but set to false")
    void shouldThrowWhenAuthenticationIsFalse() {
        // Arrange: Create a mock that explicitly fails the .isAuthenticated() check
        Authentication mockAuth = org.mockito.Mockito.mock(Authentication.class);
        when(mockAuth.isAuthenticated()).thenReturn(false);

        SecurityContextHolder.getContext().setAuthentication(mockAuth);

        // Act & Assert
        IllegalStateException ex = assertThrows(IllegalStateException.class, SecurityUtil::getCurrentUserId);
        assertEquals("No authenticated user found in Security Context", ex.getMessage());
    }

    // ==================================================================================
    // AUTHORIZATION (hasAuthority) TESTS
    // ==================================================================================

    /// Validates the safety branch: if the SecurityContext is entirely empty
    /// (authentication is null), it should safely return false instead of throwing a NullPointerException.
    @Test
    @DisplayName("Unit: hasAuthority should return false when Authentication is null")
    void shouldReturnFalseWhenAuthIsNull_HasAuthority() {
        // Arrange: Explicitly clear the context to simulate an unauthenticated thread
        SecurityContextHolder.clearContext();

        // Act
        boolean hasAdminRole = SecurityUtil.hasAuthority("ROLE_ADMIN");

        // Assert
        assertFalse(hasAdminRole, "Should safely return false when no authentication exists in the context.");
    }

    /// Verifies the "Happy Path" where the authenticated user possesses the exact
    /// authority being requested.
    @Test
    @DisplayName("Unit: hasAuthority should return true when user possesses the requested role")
    void shouldReturnTrueWhenUserHasAuthority() {
        // Arrange: Create an auth token with the ROLE_ADMIN authority
        org.springframework.security.core.authority.SimpleGrantedAuthority adminAuthority =
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN");

        Authentication auth = new UsernamePasswordAuthenticationToken(
                "adminUser", "pass", Collections.singletonList(adminAuthority)
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Act & Assert
        assertTrue(SecurityUtil.hasAuthority("ROLE_ADMIN"),
                "Should return true because the user explicitly has the ROLE_ADMIN authority.");
    }

    /// Ensures strict security boundaries by verifying that users with lower or
    /// different privileges are correctly denied.
    @Test
    @DisplayName("Unit: hasAuthority should return false when user lacks the requested role")
    void shouldReturnFalseWhenUserLacksAuthority() {
        // Arrange: Create an auth token with ONLY the ROLE_CUSTOMER authority
        org.springframework.security.core.authority.SimpleGrantedAuthority customerAuthority =
                new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER");

        Authentication auth = new UsernamePasswordAuthenticationToken(
                "standardUser", "pass", Collections.singletonList(customerAuthority)
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        // Act & Assert
        assertFalse(SecurityUtil.hasAuthority("ROLE_ADMIN"),
                "Should return false because the user only has ROLE_CUSTOMER, not ROLE_ADMIN.");
    }
}