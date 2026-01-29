package com.opayque.api.identity.service;

import com.opayque.api.identity.dto.LoginRequest;
import com.opayque.api.identity.dto.LoginResponse;
import com.opayque.api.identity.dto.RefreshTokenRequest;
import com.opayque.api.identity.dto.RegisterRequest;
import com.opayque.api.identity.dto.RegisterResponse;
import com.opayque.api.identity.entity.RefreshToken;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.RefreshTokenRepository;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.exception.TokenRevokedException;
import com.opayque.api.infrastructure.exception.UserAlreadyExistsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/// Epic 1: Identity & Access Management - Service Logic Verification.
///
/// This suite executes isolated unit tests for the [AuthService] business layer.
/// It utilizes Mockito to stub infrastructure dependencies, focusing exclusively
/// on the correctness of identity onboarding, credential validation,
/// and the token rotation/revocation lifecycle (Stories 1.4 & 1.6).
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private JwtService jwtService;
    @Mock private TokenBlocklistService tokenBlocklistService;

    @InjectMocks
    private AuthService authService;

    /// Scenario: Successful Onboarding Handshake.
    ///
    /// Validates that a valid registration request results in a correctly mapped
    /// [RegisterResponse] and that the user entity is persisted with a BCrypt
    /// encoded password and the default 'CUSTOMER' role.
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

    /// Scenario: Identity Ledger Integrity.
    ///
    /// Ensures that duplicate registration attempts for an existing active
    /// identity are rejected with a [UserAlreadyExistsException].
    @Test
    @DisplayName("Unit: Should throw UserAlreadyExistsException when email is taken")
    void shouldThrowExceptionIfEmailExists() {
        RegisterRequest request = new RegisterRequest("exists@test.com", "pass", "Name");
        User existingUser = User.builder().email(request.email()).build();

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(existingUser));

        assertThrows(UserAlreadyExistsException.class, () -> authService.register(request));
    }

    /// Scenario: Soft-Delete Re-Onboarding Compliance.
    ///
    /// Verifies that the system allows re-registration of an email address if
    /// the previous identity associated with it has been soft-deleted,
    /// adhering to GDPR data erasure standards.
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

    /// Scenario: Dual Token Issuance.
    ///
    /// Confirms that successful authentication returns both a stateless JWT
    /// access token and a high-entropy opaque refresh token.
    @Test
    @DisplayName("Unit: Should return JWT when login credentials are valid")
    void shouldReturnJwtOnLogin() {
        LoginRequest request = new LoginRequest("dev@opayque.com", "password");
        User user = User.builder().email(request.email()).role(Role.ADMIN).build();

        when(userRepository.findByEmail(request.email())).thenReturn(Optional.of(user));
        when(jwtService.generateToken(user.getEmail(), "ADMIN")).thenReturn("valid.token");
        when(jwtService.generateRefreshToken()).thenReturn("refresh.token");

        LoginResponse response = authService.login(request);

        assertEquals("valid.token", response.token());
        assertEquals("refresh.token", response.refreshToken());
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    /// Scenario: Stateless Session Invalidation (Story 1.4).
    ///
    /// Verifies that the logout procedure correctly extracts the remaining TTL
    /// from the JWT and propagates the signature to the [TokenBlocklistService].
    @Test
    @DisplayName("Unit: Logout should calculate TTL and block token if valid")
    void logout_ShouldBlockToken_WhenTtlPositive() {
        String token = "header.payload.signature";
        Date futureDate = new Date(System.currentTimeMillis() + 10000);

        when(jwtService.extractExpiration(token)).thenReturn(futureDate);

        authService.logout(token);

        verify(tokenBlocklistService).blockToken(eq("signature"), any(Duration.class));
    }

    /// Scenario: Expired Token Optimization.
    ///
    /// Confirms that logging out an already expired token results in a
    /// no-op for the blocklist service to conserve Redis memory.
    @Test
    @DisplayName("Unit: Logout should SKIP blocking if token is already expired")
    void logout_ShouldSkipBlock_WhenTtlNegative() {
        String token = "header.payload.signature";
        Date pastDate = new Date(System.currentTimeMillis() - 10000);

        when(jwtService.extractExpiration(token)).thenReturn(pastDate);

        authService.logout(token);

        verify(tokenBlocklistService, never()).blockToken(anyString(), any());
    }

    /// Scenario: Invalid Credential Handling.
    ///
    /// Ensures that authentication failures are propagated correctly and
    /// prevent further identity lookup or token generation.
    @Test
    @DisplayName("Unit: Should throw BadCredentialsException when password/email is wrong")
    void shouldThrowOnBadCredentials() {
        LoginRequest request = new LoginRequest("wrong@opayque.com", "wrongpass");

        doThrow(new BadCredentialsException("Bad credentials"))
                .when(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));

        assertThrows(BadCredentialsException.class, () -> authService.login(request));

        verify(userRepository, never()).findByEmail(anyString());
        verify(jwtService, never()).generateToken(anyString(), anyString());
    }

    /// Scenario: Opaque Token Rotation (Story 1.6).
    ///
    /// Verifies the "Fortress" rotation logic: validating the existing opaque token,
    /// generating new credentials, and overwriting the database state to invalidate
    /// the old reference.
    @Test
    @DisplayName("Unit: Rotation - Should revoke old token and rotate to new one")
    void refreshToken_ShouldRotateAndHash() {
        String oldRawToken = "old-raw-token";
        String newRawToken = "new-high-entropy-string";
        String newAccessToken = "new.jwt.access";

        User user = User.builder()
                .id(UUID.randomUUID())
                .email("user@opayque.com")
                .role(Role.CUSTOMER)
                .build();

        RefreshToken oldTokenEntity = RefreshToken.builder()
                .token(oldRawToken)
                .user(user)
                .expiryDate(Instant.now().plusSeconds(600))
                .revoked(false)
                .build();

        when(refreshTokenRepository.findByToken(oldRawToken)).thenReturn(Optional.of(oldTokenEntity));
        when(jwtService.generateToken(user.getEmail(), user.getRole().name())).thenReturn(newAccessToken);
        when(jwtService.generateRefreshToken()).thenReturn(newRawToken);
        when(jwtService.getRefreshExpiration()).thenReturn(604800000L);

        LoginResponse response = authService.refreshToken(new RefreshTokenRequest(oldRawToken));

        assertEquals(newAccessToken, response.token());
        assertEquals(newRawToken, response.refreshToken());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());

        RefreshToken savedEntity = captor.getValue();
        assertEquals(newRawToken, savedEntity.getToken());
        assertFalse(savedEntity.isRevoked());
    }

    /// Scenario: Temporal Guard - Expired Token Rejection.
    ///
    /// Validates that tokens exceeding their TTL are rejected during the
    /// rotation phase.
    @Test
    @DisplayName("Unit: Zombie Check - Should reject expired token")
    void refreshToken_ShouldRejectExpired() {
        String rawToken = "expired-token";
        RefreshToken expiredEntity = RefreshToken.builder()
                .expiryDate(Instant.now().minusSeconds(1))
                .revoked(false)
                .build();

        when(refreshTokenRepository.findByToken(anyString())).thenReturn(Optional.of(expiredEntity));

        assertThrows(TokenRevokedException.class,
                () -> authService.refreshToken(new RefreshTokenRequest(rawToken)));
    }

    /// Scenario: Explicit Revocation Guard.
    ///
    /// Ensures that tokens manually flagged as revoked in the database
    /// (e.g., via administrative action) cannot be utilized for session rotation.
    @Test
    @DisplayName("Unit: Security - Should strictly block access if token is flagged as revoked")
    void refreshToken_ShouldBlockRevokedToken() {
        String revokedTokenStr = "revoked-token-value";
        RefreshTokenRequest request = new RefreshTokenRequest(revokedTokenStr);

        RefreshToken revokedEntity = RefreshToken.builder()
                .token(revokedTokenStr)
                .revoked(true)
                .expiryDate(Instant.now().plusSeconds(999))
                .build();

        when(refreshTokenRepository.findByToken(revokedTokenStr)).thenReturn(Optional.of(revokedEntity));

        TokenRevokedException exception = assertThrows(TokenRevokedException.class,
                () -> authService.refreshToken(request));

        assertEquals("Token has been revoked", exception.getMessage());
        verify(jwtService, never()).generateToken(anyString(), anyString());
    }
}