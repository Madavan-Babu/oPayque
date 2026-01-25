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

/**
 * Epic 1: Identity & Access Management - Story 1.1 User Registration
 * * This service handles the business logic for user authentication and onboarding.
 * It ensures that sensitive data, like passwords, are never stored in plain text
 * by utilizing BCrypt hashing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    /**
     * Registers a new user within the oPayque ecosystem.
     * * Uses Spring's @Transactional to ensure that the registration process is atomic;
     * if the database save fails, no partial data will be persisted.
     *
     * @param request The validated registration data.
     * @return A sanitized RegisterResponse containing non-sensitive user metadata.
     * @throws UserAlreadyExistsException if the provided email is already registered in the system.
     */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        log.info("Attempting to register new user with email: {}", request.email());

        // 1. Identity Guardrail: Enhanced check to ensure email is not tied to an active account.
        // This logic accounts for our Soft Delete strategy.
        if (userRepository.findByEmail(request.email())
                .map(user -> user.getDeletedAt() == null)
                .orElse(false)) {
            log.warn("Registration failed: Active account with email {} already exists", request.email());
            throw new UserAlreadyExistsException("Email already in use");
        }

        // 2. Build the User entity with a one-way hashed password
        User user = User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password())) // Standardized BCrypt hashing
                .fullName(request.fullName())
                .role(Role.CUSTOMER) // Default role assignment
                .build();

        // 3. Persist the new user to the ledger
        User savedUser = userRepository.save(user);
        log.info("User successfully registered with ID: {}", savedUser.getId());

        // 4. Transform to a safe Response DTO (Opaque to internal entity structure)
        return new RegisterResponse(
                savedUser.getId(),
                savedUser.getEmail(),
                savedUser.getFullName(),
                savedUser.getRole().name()
        );
    }

    /**
     * Authenticates user credentials and returns a signed JWT.
     */
    public LoginResponse login(LoginRequest request) {
        log.info("Attempting login for user: {}", request.email());

        // 1. Let Spring Security handle the credential check
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.email(), request.password())
        );

        // 2. If we reached here, authentication was successful. Retrieve user to get roles.
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UserNotFoundException("User not found after authentication"));

        // 3. Generate the "Key Card"
        String jwtToken = jwtService.generateToken(user.getEmail(), user.getRole().name());

        log.info("Login successful for user: {}", request.email());
        return new LoginResponse(jwtToken);
    }
}