package com.opayque.api.identity.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Identity & Access Management - Security Configuration
 * * Defines the stateless security architecture for oPayque.
 * This configures the filter chain to allow public access to auth endpoints
 * while protecting the rest of the core banking API.
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    /**
     * Configures the HTTP security filter chain.
     * Sets session management to stateless to support a JWT-based architecture.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        log.info("Configuring oPayque Security Filter Chain...");

        http
                .csrf(AbstractHttpConfigurer::disable) // Required for stateless APIs
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll() // Open the "Auth" gates
                        .anyRequest().authenticated() // Lock everything else
                )
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS) // We are a stateless bank
                );

        return http.build();
    }

    /**
     * Provides a BCryptPasswordEncoder bean for secure one-way password hashing.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        log.info("Initializing BCrypt Password Encoder...");
        return new BCryptPasswordEncoder();
    }
}