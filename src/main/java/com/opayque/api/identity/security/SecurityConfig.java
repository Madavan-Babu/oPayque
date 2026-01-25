package com.opayque.api.identity.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/// Epic 1: Identity & Access Management - Security Configuration
///
/// Defines the stateless security architecture for the oPayque ecosystem.
/// This configuration orchestrates the Spring Security Filter Chain to ensure that
/// public onboarding remains accessible while the core banking API is shielded behind
/// cryptographic JWT validation.
///
/// ### Security Strategy:
/// - **Stateless Session Management**: Disables HTTP sessions to support cloud-native scalability.
/// - **JWT Interception**: Injects the {@link JwtAuthenticationFilter} before the standard username/password filter.
/// - **CSRF Protection**: Explicitly disabled as the API utilizes stateless tokens rather than browser cookies.
@Configuration
@EnableWebSecurity
@Slf4j
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;

    /// Configures the primary {@link SecurityFilterChain} for the oPayque API.
    ///
    /// This method defines the URI authorization rules and attaches the necessary
    /// authentication providers and filters.
    ///
    /// @param http The {@link HttpSecurity} object used to build the filter chain.
    /// @return The finalized {@link SecurityFilterChain}.
    /// @throws Exception If a configuration error occurs during the bean initialization.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("Initializing oPayque Opaque Security Filter Chain...");

        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // 1. Open the "Auth" gates for user onboarding and credential issuance
                        .requestMatchers("/api/v1/auth/**").permitAll()

                        // 2. All other financial endpoints (Wallets, Transactions) require a valid JWT
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        // 3. Enforce a stateless policy for high-concurrency cloud scaling
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider)

                // 4. Position the JWT filter as the primary gatekeeper before standard auth
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}