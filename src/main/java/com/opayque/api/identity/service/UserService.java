package com.opayque.api.identity.service;

import com.opayque.api.identity.dto.UserResponse;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/// Epic 1: Identity & Access Management - User Profile Management
///
/// This service handles user-centric operations with a heavy focus on data privacy
/// and BOLA (Broken Object Level Authorization) prevention.
///
/// ### Security Guarantees:
/// - **Ownership Verification**: Every request is checked against the authenticated
///   identity extracted from the JWT.
/// - **Soft-Delete Sensitivity**: Automatically filters out deactivated accounts
///   to maintain ledger integrity.
@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;

    /// Retrieves a user profile with strict BOLA enforcement.
    ///
    /// This method serves as a primary example of our "Opaque" security model:
    /// It identifies the requester first, validates ownership second, and only then
    /// allows data retrieval.
    ///
    /// @param userId The unique identifier of the resource being requested.
    /// @return A safe {@link UserResponse} DTO containing non-sensitive profile data.
    /// @throws AccessDeniedException If the requester attempts to access an ID they do not own.
    /// @throws RuntimeException (Mapped to 404) If the user does not exist or is soft-deleted.
    public UserResponse getUserProfile(UUID userId) {
        // 1. THE BOLA GATE: Extract the verified ID of the current request thread
        UUID requesterId = SecurityUtil.getCurrentUserId();

        // 2. Ownership Check: Prevents users from "guessing" other users' UUIDs
        if (!requesterId.equals(userId)) {
            log.warn("BOLA Attack Detected! User [{}] attempted to access data of User [{}]", requesterId, userId);
            throw new AccessDeniedException("You are not authorized to view this resource.");
        }

        // 3. Persistence Fetch: Retrieve data only after the gate is passed
        User user = userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null) // Respect the soft-delete compliance policy
                .orElseThrow(() -> new RuntimeException("User not found"));

        // 4. Transform to Opaque DTO to prevent internal field leakage
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getRole()
        );
    }
}