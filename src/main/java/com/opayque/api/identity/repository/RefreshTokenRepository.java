package com.opayque.api.identity.repository;

import com.opayque.api.identity.service.AuthService;
import com.opayque.api.identity.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/// Epic 1: Identity & Access Management - Story 1.6: Data Access Layer.
///
/// Provides the exclusive data access abstraction for the [RefreshToken] entity.
///
/// Architecture Rule: This repository must strictly be injected into the [AuthService]
/// to maintain a clear service boundary and prevent leaking refresh logic into the
/// controller or integration layers.
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    /// Retrieves a [RefreshToken] by its opaque string value.
    /// Utilized during the token rotation phase to validate the client's credentials.
    Optional<RefreshToken> findByToken(String token);

    /// Identifies an existing token associated with a specific user ID.
    /// Facilitates the "1:1 Overwrite Policy" where existing tokens are identified
    /// for rotation or invalidation during new authentication events.
    @Query("SELECT rt FROM RefreshToken rt WHERE rt.user.id = :userId")
    Optional<RefreshToken> findByUserId(UUID userId);
}