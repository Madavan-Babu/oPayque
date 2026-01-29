package com.opayque.api.identity.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/// Epic 1: Identity & Access Management - Story 1.6: Persistence Layer.
///
/// Represents a high-entropy, opaque "Refresh Token" utilized to maintain long-lived
/// user sessions without exposing the primary identity credentials.
///
/// Design Decision: Single Active Session (SAS) Policy.
/// This entity enforces a strict 1:1 relationship with the [User] entity.
/// Each user identity is constrained to exactly one active refresh token;
/// subsequent authentications on disparate devices will trigger a rotation
/// and invalidation of the antecedent token.
@Entity
@Table(name = "refresh_tokens")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefreshToken {

    /// Primary key utilizing a Universally Unique Identifier (UUID) for
    /// distribution-friendly identity generation.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /// The unique, high-entropy token string presented by the client.
    @Column(nullable = false, unique = true)
    private String token;

    /// The absolute temporal boundary after which the token is no longer valid.
    @Column(nullable = false)
    private Instant expiryDate;

    /// Manual revocation flag to support administrative or user-initiated "Kill Switch"
    /// scenarios (e.g., Logout).
    @Column(nullable = false)
    private boolean revoked;

    /// The Fortress Constraint: One User, One Token.
    /// Maps a 1:1 bidirectional relationship with the User entity.
    /// Lazy fetching is utilized to optimize memory usage when the user context
    /// is not explicitly required during token validation.
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", referencedColumnName = "id", unique = true, nullable = false)
    private User user;
}