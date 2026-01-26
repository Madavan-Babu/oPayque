package com.opayque.api.identity.security;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
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
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Slf4j
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("Initializing oPayque Opaque Security Filter Chain...");

        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        // 1. Open the "Auth" gates for user onboarding
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        // 2. All other endpoints require a valid JWT by default
                        .anyRequest().authenticated()
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                // 3. Exception Handling: Distinct 401 vs 403
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            // Ensures "Unknown Identity" returns 401, not 403
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized: Token missing or invalid");
                        })
                );

        return http.build();
    }
}