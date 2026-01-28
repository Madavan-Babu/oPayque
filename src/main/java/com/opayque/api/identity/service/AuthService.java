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
import com.opayque.api.infrastructure.exception.UserNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/// Epic 1: Identity & Access Management - Core Authentication Service.
///
/// Orchestrates the high-level business logic for the oPayque "Trust Layer."
/// This service manages the user lifecycle, credential verification, and token
/// state management with strict adherence to atomicity and security guardrails.
///
/// Design Patterns: Proxy (Spring Security), Facade (Orchestrating repositories/services).
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final TokenBlocklistService tokenBlocklistService;

    /// Registers a new user identity while enforcing the "Soft Delete" compliance policy.
    ///
    /// This method ensures atomicity using @Transactional. It checks for identity
    /// conflicts and persists a BCrypt-hashed representation of the credentials.
    ///
    /// @param request The validated registration payload.
    /// @return The sanitised registration response.
    /// @throws UserAlreadyExistsException If the email is already linked to an active account.
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        log.info("Processing registration for: {}", request.email());

        // Identity Guardrail: Prevent duplication of active accounts.
        if (userRepository.findByEmail(request.email())
                .map(user -> user.getDeletedAt() == null)
                .orElse(false)) {
            log.warn("Registration Conflict: Email [{}] is currently in use.", request.email());
            throw new UserAlreadyExistsException("Email already in use");
        }

        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password())) // One-way cryptographic hash
                .fullName(request.fullName())
                .role(Role.CUSTOMER)
                .build();

        User savedUser = userRepository.save(user);
        log.info("New user identity successfully established with ID: {}", savedUser.getId());

        return new RegisterResponse(
                savedUser.getId(),
                savedUser.getEmail(),
                savedUser.getFullName(),
                savedUser.getRole().name()
        );
    }

    /// Authenticates credentials and issues a Dual Token Pair (Access + Refresh).
    ///
    /// Enforces a Single Active Session (SAS) Policy: Initiating a new session
    /// automatically invalidates the antecedent refresh token.
    ///
    /// @param request The user's login credentials.
    /// @return A response containing the JWT and the opaque refresh token.
    /// @throws UserNotFoundException If identity lookup fails post-authentication.
    public LoginResponse login(LoginRequest request) {
        log.info("Authenticating user: {}", request.email());

        // Delegates credential validation to the Spring Security AuthenticationManager.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserNotFoundException("User identity not found after authentication challenge"));

        // 1. Generate temporal Access Token (JWT).
        String jwtToken = jwtService.generateToken(user.getEmail(), user.getRole().name());

        // 2. Generate high-entropy Refresh Token (Opaque).
        String refreshToken = jwtService.generateRefreshToken();

        // 3. Persist and Enforce 1:1 SAS Policy.
        saveUserRefreshToken(user, refreshToken);

        log.info("Authentication successful. Issuing Dual Tokens for user: {}", request.email());
        return new LoginResponse(jwtToken, refreshToken);
    }

    /// STORY 1.6: Token Rotation Flow.
    ///
    /// Implements secure token rotation to mitigate the risk of token theft.
    /// Validates the opaque token's existence, expiration, and revocation status before
    /// issuing a new temporal/opaque pair.
    ///
    /// @param request The opaque refresh token provided by the client.
    /// @return A new pair of access and refresh tokens.
    /// @throws TokenRevokedException If the token is missing, expired, or manually revoked.
    public LoginResponse refreshToken(RefreshTokenRequest request) {
        // 1. Verify existence in the persistence layer.
        RefreshToken storedToken = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> new TokenRevokedException("Refresh token not found"));

        // 2. Validate cryptographic and temporal state.
        if (storedToken.isRevoked()) {
            log.warn("Security Alert: Attempt to use revoked token: {}", request.refreshToken());
            throw new TokenRevokedException("Token has been revoked");
        }

        if (storedToken.getExpiryDate().isBefore(Instant.now())) {
            log.warn("Expired token usage attempt. Token ID: {}", storedToken.getId());
            throw new TokenRevokedException("Refresh token expired");
        }

        // 3. Execution of Rotation logic.
        User user = storedToken.getUser();
        String newAccessToken = jwtService.generateToken(user.getEmail(), user.getRole().name());
        String newRefreshToken = jwtService.generateRefreshToken();

        // Overwrites the existing token entity to invalidate the previous string (Rotation).
        updateTokenEntity(storedToken, newRefreshToken);

        log.info("Token Rotation Successful for User: {}", user.getId());
        return new LoginResponse(newAccessToken, newRefreshToken);
    }

    /// Internal helper: Enforces SAS Policy by upserting the unique token row.
    private void saveUserRefreshToken(User user, String token) {
        RefreshToken tokenEntity = refreshTokenRepository.findByUserId(user.getId())
                .orElse(RefreshToken.builder()
                        .user(user)
                        .build());

        updateTokenEntity(tokenEntity, token);
    }

    /// Internal helper: Synchronizes the token state across the entity and persistence layer.
    private void updateTokenEntity(RefreshToken tokenEntity, String newToken) {
        tokenEntity.setToken(newToken);
        tokenEntity.setRevoked(false);
        tokenEntity.setExpiryDate(Instant.now().plusMillis(jwtService.getRefreshExpiration()));
        refreshTokenRepository.save(tokenEntity);
    }

    /// STORY 1.4: Stateless Session Invalidation.
    ///
    /// Revokes a JWT's validity before its natural expiration by adding its
    /// unique signature to the Redis-backed blocklist for the remaining TTL.
    ///
    /// @param token The raw JWT string to be invalidated.
    public void logout(String token) {
        Date expiration = jwtService.extractExpiration(token);
        long ttlMillis = expiration.getTime() - System.currentTimeMillis();

        if (ttlMillis > 0) {
            String signature = token.substring(token.lastIndexOf(".") + 1);
            tokenBlocklistService.blockToken(signature, Duration.ofMillis(ttlMillis));
            log.info("JWT Revoked: Signature added to blocklist for remaining TTL ({} ms)", ttlMillis);
        } else {
            log.warn("Logout redundant: Token has already expired naturally.");
        }
    }
}