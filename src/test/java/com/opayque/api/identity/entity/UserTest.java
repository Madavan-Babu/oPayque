package com.opayque.api.identity.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;

/// Epic 1: Identity & Access Management - Unit Testing
///
/// This suite validates the core business logic embedded within the {@link User} entity.
/// It specifically focuses on the soft-delete lifecycle and its integration with
/// Spring Security's account enablement contract.
class UserTest {

    /// Verifies that a user identity is marked as disabled in the security context
    /// once the soft-delete timestamp is populated.
    /// This ensures compliance with "Permanent Audit Trail" requirements.
    @Test
    @DisplayName("Unit: Account should be disabled if soft-deleted")
    void shouldBeDisabledIfDeleted() {
        User user = User.builder()
                .deletedAt(LocalDateTime.now())
                .build();

        assertFalse(user.isEnabled(), "Soft-deleted user must return false for isEnabled()");
    }

    /// Confirms that active users without a deletion timestamp remain authorized
    /// for system access.
    @Test
    @DisplayName("Unit: Account should be enabled if active")
    void shouldBeEnabledIfActive() {
        User user = User.builder()
                .deletedAt(null)
                .build();

        assertTrue(user.isEnabled(), "Active user must return true for isEnabled()");
    }


    @Test
    @DisplayName("Unit: Should prefix authorities with ROLE_ for Spring Security compatibility")
    void shouldPrefixAuthoritiesWithRole() {
        // Arrange
        User user = User.builder()
                .role(Role.ADMIN)
                .build();

        // Act
        var authorities = user.getAuthorities();

        // Assert
        boolean hasAdminRole = authorities.stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

        assertTrue(hasAdminRole, "Authorities must include 'ROLE_ADMIN' to satisfy Spring's RBAC requirements.");
    }
}