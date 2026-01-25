package com.opayque.api.infrastructure.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/// Epic 1: Identity & Access Management - Infrastructure Configuration
///
/// Central configuration for Spring Security beans and identity infrastructure.
/// This class defines the mechanical beans required to bridge our PostgreSQL ledger with
/// the security filter chain.
///
/// ### Core Responsibilities:
/// - **Identity Lookup**: Defines how the system retrieves user details from the database.
/// - **Authentication Strategy**: Configures the modern DAO-based provider for credential verification.
/// - **Credential Security**: Provides the standard BCrypt encoder for "Reliability-First" password hashing.
@Configuration
@RequiredArgsConstructor
public class ApplicationConfig {

    /// Configures the {@link AuthenticationProvider} which orchestrates the credential matching logic.
    ///
    /// This implementation uses the modern "explicit" approach to configure the
    /// {@link DaoAuthenticationProvider}, avoiding deprecated patterns by
    /// directly injecting dependencies into the bean definition.
    ///
    /// @param userDetailsService The service used to load user data.
    /// @param passwordEncoder The encoder used to verify hashed passwords.
    /// @return A fully configured and non-deprecated DaoAuthenticationProvider.
    @Bean
    public AuthenticationProvider authenticationProvider(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider();
        authProvider.setUserDetailsService(userDetailsService);
        authProvider.setPasswordEncoder(passwordEncoder);
        return authProvider;
    }

    /// Exposes the {@link AuthenticationManager} bean, which is the primary API used by
    /// services to trigger the authentication process.
    ///
    /// @param config The Spring-managed authentication configuration.
    /// @return The centralized AuthenticationManager.
    /// @throws Exception If the manager cannot be retrieved from the configuration.
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /// Provides the BCrypt hashing engine for user passwords.
    ///
    /// This implementation ensures that credentials are never stored as plain text,
    /// meeting high-precision financial security standards.
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}