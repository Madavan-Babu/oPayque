package com.opayque.api.identity.service;

import com.opayque.api.identity.security.JwtAuthenticationFilter;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/// Epic 1: Identity & Access Management - User Logic Verification
///
/// This suite validates the business logic and security guardrails of the {@link UserService}.
/// It ensures that data retrieval is only performed for active, non-deleted identities
/// and that "Phantom Users" are rejected even if they possess a valid JWT.
///
/// ### Verification Focus:
/// - **Identity Persistence**: Ensuring the user actually exists in the PostgreSQL ledger.
/// - **Compliance Filtering**: Validating that soft-deleted accounts are treated as non-existent for profile lookups.
/// - **Security Context Integration**: Mocking the authenticated state required by the BOLA checks.
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserService userService;

    private User authUser;

    /// Establishes a valid, authenticated Security Context for every test case.
    ///
    /// This simulates the "Logged In" state established by the {@link JwtAuthenticationFilter},
    /// which is a prerequisite for the BOLA ownership checks performed in the service layer.
    @BeforeEach
    void setUp() {
        authUser = User.builder()
                .id(UUID.randomUUID())
                .email("test@opayque.com")
                .build();

        // Populate the ThreadLocal context with our mocked principal
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(authUser, null, Collections.emptyList())
        );
    }

    /// Verifies the system's behavior when a user's ID exists in a token but has been
    /// purged or is missing from the physical ledger (Phantom User).
    @Test
    @DisplayName("Unit: Should throw RuntimeException if user exists in Token but not in DB (Phantom User)")
    void shouldThrowIfUserNotFoundInDb() {
        // Arrange: Ownership check (BOLA) will pass, but the ledger lookup will return empty
        when(userRepository.findById(authUser.getId())).thenReturn(Optional.empty());

        // Act & Assert: Verify the system fails safe with a standard error message
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                userService.getUserProfile(authUser.getId()));

        assertEquals("User not found", ex.getMessage());
    }

    /// Validates the soft-delete compliance logic.
    ///
    /// This test ensures that users marked for deletion are correctly filtered out
    /// by the service's functional pipeline, adhering to our "Opaque" data management
    /// policy.
    @Test
    @DisplayName("Unit: Should throw RuntimeException if user is Soft-Deleted")
    void shouldThrowIfUserIsSoftDeleted() {
        // Arrange: Mock a user record found in the DB but marked with a deletedAt timestamp
        User deletedUser = User.builder()
                .id(authUser.getId())
                .deletedAt(LocalDateTime.now())
                .build();

        when(userRepository.findById(authUser.getId())).thenReturn(Optional.of(deletedUser));

        // Act & Assert: The service's .filter() should reject the deleted entity
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                userService.getUserProfile(authUser.getId()));

        assertEquals("User not found", ex.getMessage());
    }
}