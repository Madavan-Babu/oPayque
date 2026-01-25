package com.opayque.api.identity.security;

import com.opayque.api.identity.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
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
import static org.mockito.Mockito.*;

/// Epic 1: Identity & Access Management - Filter Security Testing
///
/// This suite validates the behavior of the custom JWT Authentication Filter.
/// It ensures the "Trust Layer" accurately processes the Authorization header,
/// validates the cryptographic token, and establishes the security context
/// for authorized requests.
///
/// ### Verification Focus:
/// - **Header Interception**: Validating 'Bearer' token extraction and malformed header rejection.
/// - **Context Propagation**: Ensuring successful auth populates SecurityContextHolder correctly.
/// - **Chain Integrity**: Verifying the filter chain continues regardless of auth status or early exits.
@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private UserDetailsService userDetailsService;

    @Mock
    private FilterChain filterChain;

    @InjectMocks
    private JwtAuthenticationFilter jwtFilter;

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    /// Initializes the mock HTTP environment and ensures the security context is
    /// cleared before each test to prevent state leakage between test cases.
    ///
    /// This is crucial for testing a stateless API where each request must be
    /// handled in total isolation.
    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
        SecurityContextHolder.clearContext();
    }

    /// Verifies that a valid Bearer token results in a fully authenticated SecurityContext.
    /// This is the "Happy Path" for all protected core banking endpoints.
    @Test
    @DisplayName("Unit: Should authenticate when valid Bearer token is provided")
    void shouldExtractTokenAndAuthenticate() throws ServletException, IOException {
        // Arrange: Simulate an authorized client request
        String token = "valid.jwt.token";
        String email = "dev@opayque.com";
        UserDetails userDetails = mock(UserDetails.class);

        request.addHeader("Authorization", "Bearer " + token);
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn(email);
        when(userDetailsService.loadUserByUsername(email)).thenReturn(userDetails);
        when(userDetails.getAuthorities()).thenReturn(Collections.emptyList());

        // Act: Execute the filter logic
        jwtFilter.doFilterInternal(request, response, filterChain);

        // Assert: Verify the SecurityContext is now aware of the user
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(userDetails, SecurityContextHolder.getContext().getAuthentication().getPrincipal());

        // Critical: Ensure the request is passed down the chain to the next filter or controller
        verify(filterChain).doFilter(request, response);
    }

    /// Ensures that the filter does not block requests that lack an Authorization header,
    /// allowing them to reach public endpoints like registration or login.
    @Test
    @DisplayName("Unit: Should continue chain without authentication if no header present")
    void shouldContinueChainIfNoToken() throws ServletException, IOException {
        // Act: Request arrives without headers
        jwtFilter.doFilterInternal(request, response, filterChain);

        // Assert: Context remains empty, but chain persists
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    /// Verifies that tampered or invalid tokens are rejected and do not
    /// grant access to the security context.
    @Test
    @DisplayName("Unit: Should not authenticate if token is invalid")
    void shouldNotAuthenticateOnInvalidToken() throws ServletException, IOException {
        // Arrange: Corrupt or expired token
        String token = "invalid.token";
        request.addHeader("Authorization", "Bearer " + token);
        when(jwtService.isTokenValid(token)).thenReturn(false);

        // Act
        jwtFilter.doFilterInternal(request, response, filterChain);

        // Assert: Access is denied at the context level
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    /// Validates the "Fast Exit" logic when the Authorization header is completely missing.
    /// Prevents unnecessary cryptographic processing for unauthenticated requests.
    @Test
    @DisplayName("Unit: Should exit early if Authorization header is missing")
    void shouldIgnoreNullHeader() throws ServletException, IOException {
        // Act
        jwtFilter.doFilterInternal(request, response, filterChain);

        // Assert: Ensure we never even attempted to validate a non-existent token
        verify(jwtService, never()).isTokenValid(anyString());
        verify(filterChain).doFilter(request, response);
    }

    /// Ensures that headers not using the 'Bearer' scheme (e.g., Basic Auth) are
    /// ignored by this specific filter.
    @Test
    @DisplayName("Unit: Should exit early if Authorization header is not Bearer")
    void shouldIgnoreMalformedHeader() throws ServletException, IOException {
        // Arrange: Standard Basic auth header
        request.addHeader("Authorization", "Basic somecreds");

        // Act
        jwtFilter.doFilterInternal(request, response, filterChain);

        // Assert: Skip JWT logic and proceed
        verify(jwtService, never()).isTokenValid(anyString());
        verify(filterChain).doFilter(request, response);
    }

    /// Verifies that the filter is thread-safe and respects existing authentication.
    /// Prevents redundant database lookups if the user is already authenticated.
    @Test
    @DisplayName("Unit: Should NOT re-authenticate if SecurityContext is already populated")
    void shouldSkipAuthenticationIfContextIsNotEmpty() throws ServletException, IOException {
        // Arrange
        String token = "valid.jwt";
        String email = "test@opayque.com";
        request.addHeader("Authorization", "Bearer " + token);

        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn(email);

        // Simulate existing thread-local authentication
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("existingUser", "pass", Collections.emptyList())
        );

        // Act
        jwtFilter.doFilterInternal(request, response, filterChain);

        // Assert: Branching logic should bypass the UserDetailsService
        verify(userDetailsService, never()).loadUserByUsername(anyString());
    }

    /// Final end-to-end unit verification for the internal authentication flow.
    @Test
    @DisplayName("Unit: Should verify full authentication flow inside the filter")
    void shouldAuthenticateUserWhenContextIsEmpty() throws ServletException, IOException {
        // Arrange
        String token = "valid.jwt";
        String email = "test@opayque.com";
        request.addHeader("Authorization", "Bearer " + token);

        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn(email);

        UserDetails userDetails = mock(UserDetails.class);
        when(userDetailsService.loadUserByUsername(email)).thenReturn(userDetails);
        when(userDetails.getAuthorities()).thenReturn(Collections.emptyList());

        // Act
        jwtFilter.doFilterInternal(request, response, filterChain);

        // Assert: Context must be populated after loading from details service
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(userDetailsService).loadUserByUsername(email);
    }

    /// Validates the "Safety Net" for tokens that are cryptographically valid
    /// but lack a subject (email) claim.
    @Test
    @DisplayName("Unit: Should skip authentication if token is valid but email is null")
    void shouldNotAuthenticateIfEmailIsNull() throws ServletException, IOException {
        // Arrange
        String token = "valid.token.without.email";
        request.addHeader("Authorization", "Bearer " + token);

        // Token is valid, but the subject is empty/null
        when(jwtService.isTokenValid(token)).thenReturn(true);
        when(jwtService.extractEmail(token)).thenReturn(null);

        // Act
        jwtFilter.doFilterInternal(request, response, filterChain);

        // Assert: Fail safe by not authenticating
        verify(userDetailsService, never()).loadUserByUsername(any());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }
}