package com.opayque.api.infrastructure.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/// Epic 1: Identity & Access Management - Exception Mapping Verification
///
/// Validates the transformation logic of the {@link GlobalExceptionHandler}.
/// It ensures that raw service-layer exceptions are correctly mapped to our standardized
/// {@link ErrorResponse} schema for consistent API consumption.
class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;
    private WebRequest webRequest;

    /// Configures the mock HTTP request context to simulate real-world API traffic.
    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/test/path");
        webRequest = new ServletWebRequest(request);
    }

    /// Verifies that {@link UserNotFoundException} results in a high-precision 404 response
    /// and that the internal path-stripping logic correctly formats the URI.
    @Test
    @DisplayName("Unit: Should handle UserNotFoundException and return 404")
    void shouldHandleUserNotFoundException() {
        // Arrange
        UserNotFoundException ex = new UserNotFoundException("User not found in DB");

        // Act
        ResponseEntity<ErrorResponse> response = exceptionHandler.handleUserNotFoundException(ex, webRequest);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("NOT_FOUND", response.getBody().code());
        assertEquals("User not found in DB", response.getBody().message());

        // Critical: Verify the "uri=" prefix is correctly removed for clean reporting
        assertEquals("/test/path", response.getBody().path());
    }
}