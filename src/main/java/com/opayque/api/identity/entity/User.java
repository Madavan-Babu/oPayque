package com.opayque.api.identity.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/// Epic 1: Identity & Access Management (The Trust Layer)
///
/// Represents the core User account within the oPayque ecosystem.
/// This entity serves as the foundation for authentication and role-based access control (RBAC).
///
/// ### Design Notes:
/// - **UUID Primary Key**: Prevents ID enumeration attacks by using non-sequential identifiers.
/// - **Soft Delete Strategy**: Utilizes the `deletedAt` field to maintain a permanent audit trail for compliance.
/// - **Spring Security Integration**: Implements {@link UserDetails} to integrate directly with the authentication filter chain.
@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User implements UserDetails {

    /// Unique identifier for the user.
    /// Uses UUID for enhanced security in a distributed cloud environment.
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /// Unique email address used for login and identity verification.
    /// Acts as the primary "Username" within the security context.
    @Column(unique = true, nullable = false)
    private String email;

    /// BCrypt hashed password.
    /// Plain text is never stored to comply with "Reliability-First" security standards.
    @Column(nullable = false)
    private String password;

    /// Full legal name of the account holder.
    @Column(nullable = false)
    private String fullName;

    /// Role-Based Access Control (RBAC) indicator.
    /// Differentiates between CUSTOMER and ADMIN privileges.
    @Enumerated(EnumType.STRING)
    private Role role;

    /// Timestamp indicating when the account was first created.
    /// Managed automatically by Hibernate for auditing purposes.
    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;

    /// Soft-delete timestamp.
    /// If non-null, the account is deactivated but remains in the ledger for
    /// regulatory compliance and "Phantom Money" prevention.
    private LocalDateTime deletedAt;

    /// Maps the internal {@link Role} to a Spring Security {@link GrantedAuthority}.
    /// Prefixes roles with "ROLE_" to follow standard Spring Security conventions.
    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    /// Returns the email as the unique username for the authentication process.
    @Override
    public String getUsername() {
        return email;
    }

    /// Logic to determine if the account has expired.
    /// Currently defaults to true for the MVP.
    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    /// Logic to determine if the account is locked.
    /// Currently defaults to true for the MVP.
    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /// Logic to determine if the credentials have expired.
    /// Currently defaults to true for the MVP.
    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    /// Links the account enablement status to our soft-delete strategy.
    /// If an account is soft-deleted, it is no longer considered enabled.
    @Override
    public boolean isEnabled() {
        return deletedAt == null;
    }
}