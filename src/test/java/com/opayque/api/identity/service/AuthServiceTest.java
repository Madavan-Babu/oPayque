package com.opayque.api.identity.service;

import com.opayque.api.identity.dto.RegisterRequest;
import com.opayque.api.identity.dto.RegisterResponse;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.exception.UserAlreadyExistsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Epic 1: Identity & Access Management - Unit Testing
 * * This test suite validates the core business logic of the AuthService in isolation.
 * By mocking external dependencies (Repository and PasswordEncoder), we ensure
 * the "Opaque" logic is correct without requiring a live database or security context.
 * * Verification Focus:
 * - Password Security: Ensuring BCrypt hashing is triggered.
 * - Role Integrity: Guaranteeing default role assignment.
 * - Fault Tolerance: Verifying exception handling for duplicate identities, including soft-deleted users.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private AuthService authService;

    /**
     * Test Case: Password Hashing Verification
     * Validates that the service layer correctly invokes the PasswordEncoder.
     * Storing raw passwords is a critical failure in our banking architecture.
     */
    @Test
    @DisplayName("Unit: Should hash password during registration")
    void shouldHashPassword() {
        // Arrange: Define a safe registration request
        RegisterRequest request = new RegisterRequest("unit@test.com", "RawPassword123!", "Unit Test");

        // Refactored Mock: Teach the mock to respond to findByEmail
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hashed_password");
        when(userRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        // Act: Execute the registration logic
        authService.register(request);

        // Assert: Verify that encode() was called exactly once with the raw input
        verify(passwordEncoder, times(1)).encode("RawPassword123!");
    }

    /**
     * Test Case: Default Role Assignment
     * Ensures all new users are onboarded as CUSTOMERs unless explicitly elevated
     * by an admin in a separate process.
     */
    @Test
    @DisplayName("Unit: Should always assign CUSTOMER role by default")
    void shouldAssignCustomerRole() {
        // Arrange
        RegisterRequest request = new RegisterRequest("role@test.com", "password", "Role Test");

        // Refactored Mock: Teach the mock to return empty for email availability
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());
        when(userRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        // Act
        RegisterResponse response = authService.register(request);

        // Assert: Confirm the response reflects the expected business default
        assertEquals("CUSTOMER", response.role());
    }

    /**
     * Test Case: Duplicate Identity Protection
     * Ensures that the system prevents duplicate registrations when an ACTIVE account exists.
     */
    @Test
    @DisplayName("Unit: Should throw UserAlreadyExistsException when email is taken")
    void shouldThrowExceptionWhenEmailExists() {
        // Arrange
        RegisterRequest request = new RegisterRequest("exists@test.com", "password", "Exists Test");

        // Simulate an ACTIVE user in the ledger (deletedAt is null)
        User activeUser = User.builder()
                .email("exists@test.com")
                .deletedAt(null)
                .build();

        when(userRepository.findByEmail("exists@test.com")).thenReturn(Optional.of(activeUser));

        // Act & Assert: Verify the custom infrastructure exception is thrown
        assertThrows(UserAlreadyExistsException.class, () -> authService.register(request));

        // Critical Assert: Ensure the database was never touched after the check failed
        verify(userRepository, never()).save(any());
    }

    /**
     * Test Case: Soft-Delete Recovery
     * Validates that an email is considered "available" for registration if the previous
     * owner has been soft-deleted.
     */
    @Test
    @DisplayName("Unit: Should allow registration if existing email is soft-deleted")
    void shouldAllowRegistrationWhenEmailIsSoftDeleted() {
        // Arrange
        String email = "reborn@opayque.com";
        RegisterRequest request = new RegisterRequest(email, "password123", "Reborn User");

        // Simulate a user that was soft-deleted in the past
        User deletedUser = User.builder()
                .email(email)
                .deletedAt(LocalDateTime.now())
                .build();

        when(userRepository.findByEmail(email)).thenReturn(Optional.of(deletedUser));
        when(passwordEncoder.encode(any())).thenReturn("hashed_password");
        when(userRepository.save(any())).thenAnswer(i -> i.getArguments()[0]);

        // Act
        RegisterResponse response = authService.register(request);

        // Assert: Success verifies that the guardrail successfully bypassed the deleted record
        assertNotNull(response);
        assertEquals(email, response.email());
        verify(userRepository, times(1)).save(any());
    }
}