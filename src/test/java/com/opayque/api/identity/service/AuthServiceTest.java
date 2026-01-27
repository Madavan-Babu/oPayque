package com.opayque.api.identity.service;

import com.opayque.api.identity.dto.LoginRequest;
import com.opayque.api.identity.dto.LoginResponse;
import com.opayque.api.identity.dto.RegisterRequest;
import com.opayque.api.identity.dto.RegisterResponse;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.exception.UserAlreadyExistsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/// Epic 1: Identity & Access Management - Service Logic Testing
///
/// This suite validates the business-centric rules within the {@link AuthService}.
/// It focuses on onboarding atomicity, credential verification, and the
/// logic governing token revocation (Story 1.4).
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;
    @Mock private TokenBlocklistService tokenBlocklistService;

    @InjectMocks
    private AuthService authService;

    /// Scenario: Successful Onboarding.
    /// Verifies that valid registration data results in a persisted identity with the
    /// default 'CUSTOMER' role.
    @Test
    @DisplayName("Unit: Should successfully register when valid data is provided")
    void shouldRegisterUserSuccessfully() {
        RegisterRequest request = new RegisterRequest("unit@test.com", "password", "Unit User");
        User savedUser = User.builder()
                .id(java.util.UUID.randomUUID())
                .email(request.email())
                .fullName(request.fullName())
                .role(Role.CUSTOMER)
                .build();

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(request.password())).thenReturn("encodedPass");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        RegisterResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals(savedUser.getId(), response.id());
        assertEquals("CUSTOMER", response.role());
    }

    /// Scenario: Registration Conflict.
    /// Ensures that duplicate emails are rejected to maintain the integrity of the
    /// identity ledger.
    @Test
    @DisplayName("Unit: Should throw UserAlreadyExistsException when email is taken")
    void shouldThrowExceptionIfEmailExists() {
        RegisterRequest request = new RegisterRequest("exists@test.com", "pass", "Name");
        User existingUser = User.builder().email(request.email()).build();

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(existingUser));

        assertThrows(UserAlreadyExistsException.class, () -> authService.register(request));
    }

    /// Scenario: Soft-Delete Re-Onboarding.
    /// Verifies that an email belonging to a soft-deleted identity is eligible for
    /// "re-registration" as a new record.
    @Test
    @DisplayName("Unit: Should allow registration if existing email is soft-deleted")
    void shouldAllowRegistrationIfEmailIsSoftDeleted() {
        RegisterRequest request = new RegisterRequest("deleted@test.com", "pass", "Name");
        User deletedUser = User.builder().email(request.email()).deletedAt(LocalDateTime.now()).build();
        User savedUser = User.builder().id(java.util.UUID.randomUUID()).email("deleted@test.com").role(Role.CUSTOMER).build();

        when(userRepository.findByEmail(anyString())).thenReturn(Optional.of(deletedUser));
        when(passwordEncoder.encode(anyString())).thenReturn("encoded");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);

        RegisterResponse response = authService.register(request);

        assertNotNull(response);
        verify(userRepository).save(any(User.class));
    }

    /// Scenario: Token Issuance.
    /// Verifies that valid credentials result in the generation of a signed JWT
    /// containing correct role metadata.
    @Test
    @DisplayName("Unit: Should return JWT when login credentials are valid")
    void shouldReturnJwtOnLogin() {
        LoginRequest request = new LoginRequest("dev@opayque.com", "password");
        User user = User.builder().email(request.email()).role(Role.ADMIN).build();

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user.getEmail(), "ADMIN")).thenReturn("valid.token");

        LoginResponse response = authService.login(request);

        assertEquals("valid.token", response.token());
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    /// Scenario: Logout Revocation (Story 1.4).
    /// Verifies that logging out calculates the remaining TTL correctly and blocks
    /// the token signature in Redis.
    @Test
    @DisplayName("Unit: Logout should calculate TTL and block token if valid")
    void logout_ShouldBlockToken_WhenTtlPositive() {
        String token = "header.payload.signature";
        Date futureDate = new Date(System.currentTimeMillis() + 10000);

        when(jwtService.extractExpiration(token)).thenReturn(futureDate);

        authService.logout(token);

        verify(tokenBlocklistService).blockToken(eq("signature"), any(Duration.class));
    }

    /// Scenario: Expired Logout.
    /// Ensures that tokens already past their expiration are not added to the blocklist,
    /// optimizing Redis memory usage.
    @Test
    @DisplayName("Unit: Logout should SKIP blocking if token is already expired")
    void logout_ShouldSkipBlock_WhenTtlNegative() {
        String token = "header.payload.signature";
        Date pastDate = new Date(System.currentTimeMillis() - 10000);

        when(jwtService.extractExpiration(token)).thenReturn(pastDate);

        authService.logout(token);

        verify(tokenBlocklistService, never()).blockToken(anyString(), any());
    }

    /**
     * Unit Test: shouldThrowOnBadCredentials
     * * Validates that the AuthService correctly handles and propagates a
     * BadCredentialsException when the AuthenticationManager rejects a login attempt.
     * This ensures the "Kill Switch" for invalid attempts works as expected.
     */
    @Test
    @DisplayName("Unit: Should throw BadCredentialsException when password/email is wrong")
    void shouldThrowOnBadCredentials() {
        // Arrange: Create a request with credentials that will fail authentication
        LoginRequest request = new LoginRequest("wrong@opayque.com", "wrongpass");

        // Mock: Simulate the AuthenticationManager throwing the standard Security exception
        doThrow(new BadCredentialsException("Bad credentials"))
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));

        // Act & Assert: Verify the service propagates the exception and halts further logic
        assertThrows(BadCredentialsException.class, () -> authService.login(request));

        // Audit: Ensure we never proceeded to look up the user or generate a JWT
        verify(userRepository, never()).findByEmail(anyString());
        verify(jwtService, never()).generateToken(anyString(), anyString());
    }
}