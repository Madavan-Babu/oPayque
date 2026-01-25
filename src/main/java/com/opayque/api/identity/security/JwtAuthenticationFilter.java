package com.opayque.api.identity.security;

import com.opayque.api.identity.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/// Epic 1: Identity & Access Management - Stateless Security Filter
/// * This industrial-grade security filter intercepts every incoming HTTP request
/// to validate JSON Web Tokens (JWTs). It acts as the "Opaque" bridge between
/// raw HTTP headers and the Spring Security Context.
/// * Logic Flow:
/// 1. Extract 'Authorization' header.
/// 2. Validate cryptographic signature via [JwtService].
/// 3. Load identity from the database.
/// 4. Populate [SecurityContextHolder] for stateless authorization.
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    /// Intercepts the request to check for a valid Bearer token.
    /// * Ensures that the filter executes exactly once per request to maintain
    /// high-performance standards in our containerized AWS environment.
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String userEmail;

        // 1. Quick exit: If the header is missing or malformed, we delegate to the next filter
        // Public endpoints (like registration) will be allowed by the SecurityConfig later.
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        jwt = authHeader.substring(7); // Extract the token segment after "Bearer "

        // 2. Perform cryptographic and temporal validation via JwtService
        if (jwtService.isTokenValid(jwt)) {
            userEmail = jwtService.extractEmail(jwt);

            // 3. Authenticate only if a user is present in token and not yet authenticated in this thread
            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                log.debug("Found valid JWT for user: {}. Initializing authentication...", userEmail);

                UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);

                // Create an authentication object using the credentials and roles found in the ledger
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );

                // Enrich the authentication with request-based details (IP, SessionID)
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // 4. Update the Security Context to allow subsequent logic (Wallet/Transfers) to trust this user
                SecurityContextHolder.getContext().setAuthentication(authToken);
                log.info("Successfully established Security Context for user: {}", userEmail);
            }
        } else {
            log.warn("Invalid or expired JWT detected for a request to: {}", request.getServletPath());
        }

        // Always continue the filter chain
        filterChain.doFilter(request, response);
    }
}