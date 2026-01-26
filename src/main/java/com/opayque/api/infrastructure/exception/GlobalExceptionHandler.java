package com.opayque.api.infrastructure.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDeniedException;

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
    /// @param request The web request context.
    /// @return A 409 Conflict response with a standardized ErrorResponse.
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserExists(UserAlreadyExistsException ex, WebRequest request) {
        log.debug("Conflict detected: Registration attempt with existing email. Reason: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /// Handles Bean Validation failures triggered by the `@Valid` annotation on
    /// incoming DTO payloads.
    ///
    /// *Note: This remains distinct as it returns a Map of fields, not a single message.*
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
    /// @param ex The UserNotFoundException thrown by the service layer.
    /// @param request The current web request used to extract the URI path.
    /// @return A 404 Not Found response containing detailed error metadata.
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFoundException(UserNotFoundException ex, WebRequest request) {
        log.error("Resource not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /// Intercepts Spring Security's authentication failures to prevent unauthorized entry.
    ///
    /// @param ex The BadCredentialsException thrown by the AuthenticationManager.
    /// @param request The current web request.
    /// @return A 401 Unauthorized status with a sanitized ErrorResponse.
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleBadCredentials(BadCredentialsException ex, WebRequest request) {
        log.warn("Authentication failure on path [{}]: {}",
                request.getDescription(false).replace("uri=", ""),
                ex.getMessage());

        return buildErrorResponse(
                HttpStatus.UNAUTHORIZED,
                "INVALID_CREDENTIALS",
                "Invalid email or password",
                request
        );
    }

    /// Security Gate Handler
    /// Catches generic 403 errors (AccessDenied) and Spring Security 6 specific errors (AuthorizationDenied).
    /// This prevents the system from leaking a 500 Internal Server Error when a user is simply blocked.
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ErrorResponse> handleAccessDenied(Exception ex, WebRequest request) {
        log.warn("Security Gate Blocked: {} - {}", request.getDescription(false), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.FORBIDDEN,
                "Access Denied: You do not have permission to view this resource",
                request
        );
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

        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An internal service error occurred.",
                request
        );
    }

    // ==================================================================================
    //                                 PRIVATE HELPERS
    // ==================================================================================

    /// Convenience Helper: Uses the HTTP Status Name as the Error Code.
    /// Example: 404 -> "NOT_FOUND"
    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status,
            String message,
            WebRequest request
    ) {
        return buildErrorResponse(status, status.name(), message, request);
    }

    /// Master Error Factory
    /// Allows specifying a custom 'code' (e.g., "INVALID_CREDENTIALS") different from the Status Name.
    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status,
            String code,
            String message,
            WebRequest request
    ) {
        ErrorResponse errorResponse = new ErrorResponse(
                status.value(),
                code,
                message,
                request.getDescription(false).replace("uri=", "")
        );

        return new ResponseEntity<>(errorResponse, status);
    }
}