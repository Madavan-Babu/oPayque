package com.opayque.api.identity.controller;

import org.springframework.security.access.AccessDeniedException;
import com.opayque.api.identity.dto.UserResponse;
import com.opayque.api.identity.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/// Epic 1: Identity & Access Management - User Profile Controller
///
/// Provides secure endpoints for managing and retrieving user identity metadata.
/// This controller acts as a thin wrapper around the {@link UserService}, delegating
/// all authorization and BOLA enforcement logic to the service layer.
///
/// ### Security Architecture:
/// - **Path Variable Mapping**: Utilizes UUIDs to prevent sequential ID guessing.
/// - **BOLA Protection**: Relying on the internal service gate to verify that the
///   authenticated requester owns the resource they are requesting.
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /// Secure Profile Access Endpoint.
    ///
    /// Fetches the profile details for a specific user ID.
    /// Access is strictly limited to the account owner; any attempt by an external user
    /// to access this URI will result in an {@link AccessDeniedException}.
    ///
    /// @param id The UUID of the user profile to retrieve.
    /// @return A 200 OK response containing the {@link UserResponse} DTO.
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserProfile(@PathVariable UUID id) {
        // Delegation to the service layer ensures that ownership is verified via SecurityUtil
        // before any database lookup occurs.
        return ResponseEntity.ok(userService.getUserProfile(id));
    }
}