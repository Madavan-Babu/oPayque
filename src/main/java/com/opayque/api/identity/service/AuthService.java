package com.opayque.api.identity.service;

import com.opayque.api.identity.dto.LoginRequest;
import com.opayque.api.identity.dto.LoginResponse;
import com.opayque.api.identity.dto.RegisterRequest;
import com.opayque.api.identity.dto.RegisterResponse;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
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
import java.util.Date;

/// Epic 1: Identity & Access Management - Authentication Service
///
/// This service orchestrates the high-level business logic for the oPayque "Trust Layer."
/// It manages user persistence with atomicity, credential verification via Spring Security,
/// and token lifecycle management.
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final TokenBlocklistService tokenBlocklistService;

    /// Registers a new user identity while enforcing the "Soft Delete" compliance policy.
    ///
    /// @param request The user data to persist.
    /// @return A safe DTO containing the saved identity.
    /// @throws UserAlreadyExistsException if an active identity with the email already exists.
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        log.info("Processing registration for: {}", request.email());

        // Identity Guardrail: Ensure we don't duplicate active accounts
        if (userRepository.findByEmail(request.email())
                .map(user -> user.getDeletedAt() == null)
                .orElse(false)) {
            log.warn("Registration Conflict: Email [{}] is currently in use.", request.email());
            throw new UserAlreadyExistsException("Email already in use");
        }

        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password())) // One-way BCrypt hash
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

    /// Authenticates credentials and issues a "Key Card" (JWT) for the system.
    ///
    /// @param request The email and password provided by the client.
    /// @return A signed token for subsequent authorized requests.
    public LoginResponse login(LoginRequest request) {
        log.info("Authenticating user: {}", request.email());

        // Delegate to the manager for standardized credential matching
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserNotFoundException("User identity not found after authentication challenge"));

        // Generate the cryptographically signed token with role claims
        String jwtToken = jwtService.generateToken(user.getEmail(), user.getRole().name());

        log.info("Authentication successful. Issuing JWT for user: {}", request.email());
        return new LoginResponse(jwtToken);
    }

    /// STORY 1.4: Revokes a JWT's validity before its natural expiration.
    ///
    /// Calculates the remaining TTL and persists the signature to Redis to prevent
    /// "Ghost Session" attacks in our stateless environment.
    ///
    /// @param token The raw Bearer token string.
    public void logout(String token) {
        Date expiration = jwtService.extractExpiration(token);
        long ttlMillis = expiration.getTime() - System.currentTimeMillis();

        if (ttlMillis > 0) {
            // Extract the signature segment for the blocklist key
            String signature = token.substring(token.lastIndexOf(".") + 1);
            tokenBlocklistService.blockToken(signature, Duration.ofMillis(ttlMillis));
            log.info("JWT Revoked: Signature added to blocklist for remaining TTL ({} ms)", ttlMillis);
        } else {
            log.warn("Logout redundant: Token has already expired naturally.");
        }
    }
}