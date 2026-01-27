package com.opayque.api.identity.security;

import com.opayque.api.identity.service.JwtService;
import com.opayque.api.identity.service.TokenBlocklistService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import java.io.IOException;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/// Epic 1: Identity & Access Management - Unit Testing
///
/// This suite validates the mechanical logic within the {@link JwtAuthenticationFilter}.
/// It ensures that the filter correctly intercepts headers, validates signatures against
/// the blocklist, and establishes thread-local identity.
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock private JwtService jwtService;
    @Mock private UserDetailsService userDetailsService;
    @Mock private TokenBlocklistService tokenBlocklistService;
    @Mock private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter jwtFilter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    /// Verifies the successful identity establishment from a valid Bearer token.
    @Test
    @DisplayName("Unit: Should authenticate when valid Bearer token is provided")
    void shouldExtractTokenAndAuthenticate() throws ServletException, IOException {
        String token = "valid.jwt.token";
        String email = "dev@opayque.com";
        UserDetails userDetails = mock(UserDetails.class);

        request.addHeader("Authorization", "Bearer " + token);

        when(tokenBlocklistService.isBlocked(anyString())).thenReturn(false);
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn(email);
        when(userDetailsService.loadUserByUsername(email)).thenReturn(userDetails);
        when(userDetails.getAuthorities()).thenReturn(Collections.emptyList());

        jwtFilter.doFilterInternal(request, response, filterChain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(userDetails, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        verify(filterChain).doFilter(request, response);
    }

    /// Verifies that tokens with blacklisted signatures (Story 1.4) are
    /// immediately rejected with a 401 response.
    @Test
    @DisplayName("Unit: Should block request if token is in blocklist")
    void shouldBlockRequestIfTokenIsRevoked() throws ServletException, IOException {
        String token = "header.payload.signature123";
        request.addHeader("Authorization", "Bearer " + token);

        // Simulated Blocklist hit for the signature segment
        when(tokenBlocklistService.isBlocked("signature123")).thenReturn(true);

        jwtFilter.doFilterInternal(request, response, filterChain);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        assertTrue(response.getContentAsString().contains("Token has been revoked"));
        verify(filterChain, never()).doFilter(request, response);
    }

    /// Validates the "Zero-Interference" policy for requests without auth headers.
    @Test
    @DisplayName("Unit: Should continue chain without authentication if no header present")
    void shouldContinueChainIfNoToken() throws ServletException, IOException {
        jwtFilter.doFilterInternal(request, response, filterChain);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    /// Ensures that cryptographically invalid tokens are rejected.
    @Test
    @DisplayName("Unit: Should not authenticate if token is invalid")
    void shouldNotAuthenticateOnInvalidToken() throws ServletException, IOException {
        String token = "invalid.token";
        request.addHeader("Authorization", "Bearer " + token);

        when(tokenBlocklistService.isBlocked(anyString())).thenReturn(false);
        when(jwtService.isTokenValid(token)).thenReturn(false);

        jwtFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    /// Verifies the "Fast-Exit" logic when no header is detected.
    @Test
    @DisplayName("Unit: Should exit early if Authorization header is missing")
    void shouldIgnoreNullHeader() throws ServletException, IOException {
        jwtFilter.doFilterInternal(request, response, filterChain);
        verify(jwtService, never()).isTokenValid(anyString());
        verify(filterChain).doFilter(request, response);
    }

    /// Ensures the filter ignores non-Bearer schemes, adhering to standard security protocols.
    @Test
    @DisplayName("Unit: Should exit early if Authorization header is not Bearer")
    void shouldIgnoreMalformedHeader() throws ServletException, IOException {
        request.addHeader("Authorization", "Basic somecreds");
        jwtFilter.doFilterInternal(request, response, filterChain);
        verify(jwtService, never()).isTokenValid(anyString());
        verify(filterChain).doFilter(request, response);
    }

    /// Validates thread-safety and prevents redundant re-authentication if the
    /// context is already established.
    @Test
    @DisplayName("Unit: Should NOT re-authenticate if SecurityContext is already populated")
    void shouldSkipAuthenticationIfContextIsNotEmpty() throws ServletException, IOException {
        String token = "valid.jwt";
        String email = "test@opayque.com";
        request.addHeader("Authorization", "Bearer " + token);

        when(tokenBlocklistService.isBlocked(anyString())).thenReturn(false);
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn(email);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("existingUser", "pass", Collections.emptyList())
        );

        jwtFilter.doFilterInternal(request, response, filterChain);

        verify(userDetailsService, never()).loadUserByUsername(anyString());
        verify(filterChain).doFilter(request, response);
    }

    /// Ensures that valid tokens without an identity claim (email) do not
    /// result in authentication.
    @Test
    @DisplayName("Unit: Should skip authentication if token is valid but email is null")
    void shouldNotAuthenticateIfEmailIsNull() throws ServletException, IOException {
        String token = "valid.token.without.email";
        request.addHeader("Authorization", "Bearer " + token);

        when(tokenBlocklistService.isBlocked(anyString())).thenReturn(false);
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn(null);

        jwtFilter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    /**
     * Unit Test: shouldAuthenticateUserWhenContextIsEmpty
     * * Verifies the full "Happy Path" of the security filter: extracting a token,
     * checking the blocklist, validating identity, and establishing trust in the context.
     */
    @Test
    @DisplayName("Unit: Should verify full authentication flow inside the filter")
    void shouldAuthenticateUserWhenContextIsEmpty() throws ServletException, IOException {
        // Arrange: Setup request with a valid Bearer token segment
        String token = "valid.jwt.signature";
        String email = "test@opayque.com";
        request.addHeader("Authorization", "Bearer " + token);

        // Mock: Pass the blocklist check and cryptographic validation
        when(tokenBlocklistService.isBlocked(anyString())).thenReturn(false);
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn(email);

        // Mock: Simulate loading the user identity from the ledger
        UserDetails userDetails = mock(UserDetails.class);
        when(userDetailsService.loadUserByUsername(email)).thenReturn(userDetails);
        when(userDetails.getAuthorities()).thenReturn(Collections.emptyList());

        // Act: Execute the filter logic
        jwtFilter.doFilterInternal(request, response, filterChain);

        // Assert: Verify the thread now holds the authenticated principal
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(userDetails, SecurityContextHolder.getContext().getAuthentication().getPrincipal());

        // Assert: Ensure the request was handed off to the next filter in the chain
        verify(userDetailsService).loadUserByUsername(email);
        verify(filterChain).doFilter(request, response);
    }
}