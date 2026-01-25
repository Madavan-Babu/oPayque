package com.opayque.api.infrastructure.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.HashMap;
import java.util.Map;

/// Epic 1: Identity & Access Management - Infrastructure Layer
///
/// This global interceptor catches exceptions thrown across all controllers
/// to provide a standardized, client-friendly error response format.
///
/// ### Design Philosophy:
/// - **Consistency**: All validation and business logic failures return a uniform JSON schema via {@link ErrorResponse}.
/// - **Security**: Prevents leakage of internal stack traces to external clients while maintaining full observability in the logs.
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /// Handles business logic conflicts, specifically when a user attempts to register
    /// with an email that is already persisted in the ledger.
    ///
    /// @param ex The custom UserAlreadyExistsException.
    /// @return A 409 Conflict response with the specific error message.
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<String> handleUserExists(UserAlreadyExistsException ex) {
        log.debug("Conflict detected: Registration attempt with existing email. Reason: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    /// Handles Bean Validation failures triggered by the `@Valid` annotation on
    /// incoming DTO payloads.
    ///
    /// @param ex Exception thrown when argument validation fails.
    /// @return A 400 Bad Request containing a map of field names and their corresponding error messages.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        log.warn("Validation failed for incoming request. Error count: {}", ex.getBindingResult().getErrorCount());

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(errors);
    }

    /// Handles instances where a requested user identity cannot be found during login or audit lookups.
    ///
    /// Transforms the exception into a standardized {@link ErrorResponse} for consistent client-side handling.
    ///
    /// @param ex The UserNotFoundException thrown by the service layer.
    /// @param request The current web request used to extract the URI path.
    /// @return A 404 Not Found response containing detailed error metadata.
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFoundException(UserNotFoundException ex, WebRequest request) {
        log.error("Resource not found: {}", ex.getMessage());
        ErrorResponse error = new ErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "NOT_FOUND",
                ex.getMessage(),
                request.getDescription(false).replace("uri=", "")
        );
        return new ResponseEntity<>(error, HttpStatus.NOT_FOUND);
    }

    /// Intercepts Spring Security's authentication failures to prevent unauthorized entry.
    ///
    /// This handler provides a standardized 401 response while ensuring internal security
    /// logs capture the context of the failed attempt for auditing purposes.
    ///
    /// @param ex The BadCredentialsException thrown by the AuthenticationManager.
    /// @param request The current web request.
    /// @return A 401 Unauthorized status with a sanitized ErrorResponse.
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, WebRequest request) {
        log.warn("Authentication failure on path [{}]: {}",
                request.getDescription(false).replace("uri=", ""),
                ex.getMessage());

        ErrorResponse error = new ErrorResponse(
                HttpStatus.UNAUTHORIZED.value(),
                "INVALID_CREDENTIALS",
                "Invalid email or password",
                request.getDescription(false).replace("uri=", "")
        );
        return new ResponseEntity<>(error, HttpStatus.UNAUTHORIZED);
    }

    /// Fallback handler for unexpected internal server errors.
    ///
    /// Ensures that sensitive internal logic is not leaked while providing full visibility
    /// in CloudWatch through detailed error logging.
    ///
    /// @param ex The generic Exception caught by the framework.
    /// @param request The current web request.
    /// @return A 500 Internal Server Error status with a generic ErrorResponse.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGlobalException(Exception ex, WebRequest request) {
        log.error("CRITICAL: Unexpected system failure encountered on path: {}",
                request.getDescription(false).replace("uri=", ""), ex);

        ErrorResponse error = new ErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "INTERNAL_SERVER_ERROR",
                "An internal service error occurred.",
                request.getDescription(false).replace("uri=", "")
        );
        return new ResponseEntity<>(error, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}