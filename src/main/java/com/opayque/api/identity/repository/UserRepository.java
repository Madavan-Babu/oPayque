package com.opayque.api.identity.repository;

import com.opayque.api.identity.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.UUID;
import java.util.Optional;

/// Epic 1: Identity & Access Management - Data Access Layer
/// * This repository provides the abstraction for performing CRUD operations on the [User] entity
/// within the PostgreSQL ledger. It utilizes UUIDs as primary keys to enhance security against
/// ID enumeration attacks.
@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /// Retrieves an active or soft-deleted user by their unique email address.
    /// * @param email The unique email identifier for the account.
    /// @return An [Optional] containing the user if found, or empty if no record exists.
    Optional<User> findByEmail(String email);
}