package com.opayque.api.identity.security;

import com.opayque.api.identity.service.JwtService;
import com.opayque.api.identity.service.TokenBlocklistService;
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

/// Epic 1: Identity & Access Management - Stateless Security Gatekeeper
///
/// This filter intercepts every incoming request to perform cryptographic
/// validation of JWTs and check the **Story 1.4: Kill Switch** blocklist.
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final TokenBlocklistService tokenBlocklistService;

    /// Per-request logic for token extraction, revocation checking, and identity establishment.
    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        final String jwt;
        final String userEmail;

        // 1. Quick Exit: Validate header presence and Bearer scheme
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        jwt = authHeader.substring(7);

        // 2. STORY 1.4: REVOCATION CHECK
        // Extract the unique signature segment of the JWT to check the Redis Blocklist.
        // This provides an "Opaque" way to revoke individual tokens without database lookups.
        String signature = jwt.substring(jwt.lastIndexOf(".") + 1);

        if (tokenBlocklistService.isBlocked(signature)) {
            log.warn("Blocked Token Attempt: Rejected request with revoked signature segment.");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":401,\"code\":\"UNAUTHORIZED\",\"message\":\"Token has been revoked.\"}");
            return;
        }

        // 3. Authenticate: Verify cryptographic integrity and establish SecurityContext
        if (jwtService.isTokenValid(jwt)) {
            userEmail = jwtService.extractEmail(jwt);

            if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = this.userDetailsService.loadUserByUsername(userEmail);

                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                );

                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        } else {
            log.warn("Invalid/Expired JWT: Access denied for request path: {}", request.getServletPath());
        }

        filterChain.doFilter(request, response);
    }
}