package com.opayque.api.infrastructure.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Epic 1: Identity & Access Management - Exception Mapping Verification
///
/// This suite validates the transformation of raw system exceptions into
/// standardized, client-friendly {@link ErrorResponse} DTOs. It ensures
/// consistency across all error-prone flows in the API.
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;
    private WebRequest webRequest;

    /// Initializes the mock web environment and interceptor before each test.
    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/test/path");
        webRequest = new ServletWebRequest(request);
    }

    /// Verifies that missing user identities result in a 404 Not Found response
    /// with the correct path metadata.
    @Test
    @DisplayName("Unit: Should handle UserNotFoundException and return 404")
    void shouldHandleUserNotFoundException() {
        UserNotFoundException ex = new UserNotFoundException("User not found in DB");
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleUserNotFoundException(ex, webRequest);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("NOT_FOUND", response.getBody().code());
        assertEquals("/test/path", response.getBody().path());
    }

    /// Validates the mapping for the **Story 1.4: Kill Switch**, ensuring that
    /// revoked tokens return a 401 Unauthorized status.
    @Test
    @DisplayName("Unit: Should handle TokenRevokedException and return 401")
    void shouldHandleTokenRevokedException() {
        TokenRevokedException ex = new TokenRevokedException("Session terminated");
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleTokenRevoked(ex, webRequest);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("TOKEN_REVOKED", response.getBody().code());
        assertEquals("Session terminated", response.getBody().message());
    }

    /// Confirms that failed credential challenges result in a standardized
    /// "INVALID_CREDENTIALS" response for security audits.
    @Test
    @DisplayName("Unit: Should handle BadCredentialsException and return 401")
    void shouldHandleBadCredentialsException() {
        BadCredentialsException ex = new BadCredentialsException("Wrong password");
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleBadCredentials(ex, webRequest);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("INVALID_CREDENTIALS", body.code());
    }

    /// Verifies that unexpected failures are caught and returned as a 500 status
    /// without leaking sensitive stack traces.
    @Test
    @DisplayName("Unit: Should handle generic Exception and return 500")
    void shouldHandleGlobalException() {
        Exception ex = new RuntimeException("Unexpected error");
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleGlobalException(ex, webRequest);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("An internal service error occurred.", body.message());
    }

    /// Validates the security gate behavior, ensuring that forbidden access
    /// returns a clean 403 Forbidden response.
    @Test
    @DisplayName("Unit: Should handle AccessDeniedException and return 403")
    void shouldHandleAccessDeniedException() {
        AccessDeniedException ex = new AccessDeniedException("Not allowed");
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleAccessDenied(ex, webRequest);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("Access Denied: You do not have permission to view this resource", body.message());
    }

    /// Validates that domain validation errors (e.g., "User not found") are correctly
    /// mapped to a 400 Bad Request status.
    @Test
    @DisplayName("Unit: Should handle IllegalArgumentException and return 400")
    void shouldHandleIllegalArgumentException() {
        IllegalArgumentException ex = new IllegalArgumentException("Invalid argument provided");
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleIllegalArgument(ex, webRequest);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("BAD_REQUEST", body.code());
        assertEquals("Invalid argument provided", body.message());
    }

    /// Validates that dependency failures (e.g., Currency Exchange offline) are
    /// correctly mapped to a 503 Service Unavailable status.
    @Test
    @DisplayName("Unit: Should handle ServiceUnavailableException and return 503")
    void shouldHandleServiceUnavailableException() {
        // Arrange
        ServiceUnavailableException ex = new ServiceUnavailableException("External Currency Provider Unavailable");

        // Act
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleServiceUnavailable(ex, webRequest);

        // Assert
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());

        ErrorResponse body = response.getBody();
        assertNotNull(body);
        assertEquals("SERVICE_UNAVAILABLE", body.code());
        assertEquals("External Currency Provider Unavailable", body.message());
        // Verify path metadata is preserved
        assertEquals("/test/path", body.path());
    }
}