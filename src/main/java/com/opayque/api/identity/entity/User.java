package com.opayque.api.identity.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Epic 1: Identity & Access Management (The Trust Layer)
 * * Represents the core User account within the oPayque ecosystem.
 * This entity serves as the foundation for authentication and role-based access.
 * * Design Notes:
 * - Uses UUID for the primary key to prevent ID enumeration attacks.
 * - Implements a Soft Delete strategy via the 'deletedAt' field to maintain
 * permanent audit trails.
 */
@Entity
@Table(name = "users")
@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class User {

    /**
     * Unique identifier for the user.
     * Uses UUID (Universally Unique Identifier) for enhanced security in a distributed system.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**
     * Unique email address used for login and identity verification.
     */
    @Column(unique = true, nullable = false)
    private String email;

    /**
     * BCrypt hashed password.
     * Plain text is never stored to comply with "Reliability-First" security standards.
     */
    @Column(nullable = false)
    private String password;

    /**
     * Full legal name of the account holder.
     */
    @Column(nullable = false)
    private String fullName;

    /**
     * Role-Based Access Control (RBAC) indicator.
     * Used to differentiate between CUSTOMER and ADMIN privileges.
     */
    @Enumerated(EnumType.STRING)
    private Role role;

    /**
     * Timestamp indicating when the account was first created.
     */
    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /**
     * Soft-delete timestamp.
     * If non-null, the account is considered deactivated but remains in the
     * ledger for compliance and auditing purposes.
     */
    private LocalDateTime deletedAt;
}
