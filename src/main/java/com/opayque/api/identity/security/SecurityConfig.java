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

/// Epic 1: Identity & Access Management - Security Infrastructure.
///
/// Defines the stateless security architecture for the oPayque ecosystem.
/// This configuration orchestrates the Spring Security Filter Chain to ensure that
/// public onboarding remains accessible while core banking endpoints are shielded
/// behind cryptographic JWT validation.
///
/// Compliance: Adheres to OWASP API Security Top 10 and PCI-DSS requirements for
/// transport security and session management.
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Slf4j
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;

    /// Configures the primary Security Filter Chain.
    ///
    /// Implements defense-in-depth through strict header management, stateless
    /// session policies, and explicit request authorization boundaries.
    ///
    /// @param http The HttpSecurity object to configure.
    /// @return A fully configured SecurityFilterChain instance.
    /// @throws Exception If an error occurs during the configuration process.
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("Initializing oPayque Opaque Security Filter Chain...");

        http
                .csrf(AbstractHttpConfigurer::disable) // Disabled for stateless REST APIs

                // --- Security Hardening Headers (Story 1.5) ---
                /// Enforces browser-side security constraints to mitigate common
                /// attack vectors such as Clickjacking, XSS, and MITM.
                .headers(headers -> headers
                        // 1. HSTS (Strict-Transport-Security): Forces HTTPS for 1 year.
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31536000)
                        )
                        // 2. CSP (Content Security Policy): Restricts source of executable content.
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'self'; frame-ancestors 'none';")
                        )
                        // 3. Frame Options: Prevents UI Redressing/Clickjacking attacks.
                        .frameOptions(frame -> frame.deny())

                        // 4. Cache Control: Prevents sensitive banking data from being cached on disks.
                        .cacheControl(cache -> {})

                        // 5. XSS Protection: Manual injection for legacy browser compliance.
                        .addHeaderWriter((request, response) -> {
                            response.setHeader("X-XSS-Protection", "1; mode=block");
                        })
                )

                // --- Request Authorization ---
                /// Defines the public vs. protected service boundaries.
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**", "/api/v1/security-check").permitAll()
                        .anyRequest().authenticated()
                )

                // --- Session & Authentication Management ---
                /// Enforces a strictly stateless architecture. No server-side session
                /// state is maintained.
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authenticationProvider(authenticationProvider)

                // Injects custom JWT validation before standard username/password processing.
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)

                // --- Exception Handling ---
                /// Custom entry point to handle unauthorized access attempts.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized: Token missing or invalid");
                        })
                );

        return http.build();
    }
}