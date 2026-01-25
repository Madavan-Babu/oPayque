package com.opayque.api.identity.service;

import com.opayque.api.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/// Epic 1: Identity & Access Management - Spring Security Adapter
///
/// Dedicated service for translating oPayque ledger records into Spring Security principals.
/// This component is isolated from the main AuthService to prevent circular dependencies
/// with the {@link AuthenticationManager}.
@Service
@RequiredArgsConstructor
public class CustomUserDetailsService implements UserDetailsService {

    private final UserRepository userRepository;

    /// Loads an identity from the PostgreSQL ledger using the email as the primary key.
    ///
    /// This method is invoked by the security provider during the authentication
    /// challenge to verify that the requesting user exists.
    ///
    /// @param username The email identifier provided during login.
    /// @return A {@link UserDetails} implementation compatible with the filter chain.
    /// @throws UsernameNotFoundException If the identity cannot be located in the ledger.
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return userRepository.findByEmail(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }
}