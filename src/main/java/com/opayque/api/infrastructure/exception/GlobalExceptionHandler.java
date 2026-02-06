package com.opayque.api.infrastructure.exception;

import com.opayque.api.infrastructure.dto.ErrorResponse;
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
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /// Handles business logic conflicts, specifically during user registration.
    ///
    /// @param ex The custom UserAlreadyExistsException.
    /// @param request The web request context.
    /// @return A 409 Conflict response with a standardized {@link ErrorResponse}.
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserExists(UserAlreadyExistsException ex, WebRequest request) {
        log.debug("Conflict detected: Registration attempt with existing email. Reason: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /// Handles transaction rejections due to insufficient wallet balance.
    /// Uses 402 PAYMENT_REQUIRED to explicitly signal a lack of funds,
    /// allowing clients to trigger "Top Up" flows distinct from generic validation errors.
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<ErrorResponse> handleInsufficientFunds(InsufficientFundsException ex, WebRequest request) {
        log.warn("Transaction Rejected - Insufficient Funds: {}", ex.getMessage());
        // 402 Payment Required: The perfect semantic fit for "You're broke".
        return buildErrorResponse(HttpStatus.PAYMENT_REQUIRED, ex.getMessage(), request);
    }

    /// Handles duplicate request attempts intercepted by the Idempotency Engine.
    ///
    /// @param ex The exception thrown when a locked or completed key is accessed again.
    /// @param request The web request context.
    /// @return A 409 Conflict response containing the explicit reason (e.g., "Transaction `xyz` already processed").
    @ExceptionHandler(IdempotencyException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyException(IdempotencyException ex, WebRequest request) {
        log.warn("Idempotency Conflict: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    /// Handles Bean Validation failures triggered by the `@Valid` annotation on incoming DTO payloads.
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

    /// Handles instances where a requested user identity cannot be found in the ledger.
    ///
    /// @param ex The {@link UserNotFoundException} thrown by the service layer.
    /// @param request The current web request used to extract the URI path.
    /// @return A 404 Not Found response containing detailed error metadata.
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleUserNotFoundException(UserNotFoundException ex, WebRequest request) {
        log.error("Resource not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    /// Intercepts Spring Security's authentication failures.
    ///
    /// @param ex The {@link BadCredentialsException} thrown by the AuthenticationManager.
    /// @param request The current web request.
    /// @return A 401 Unauthorized status with a sanitized {@link ErrorResponse}.
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

    /// Handles attempts to use a JWT that has been revoked via the **Story 1.4: Kill Switch**.
    ///
    /// @param ex The {@link TokenRevokedException} thrown when a blacklisted signature is detected.
    /// @param request The web request context.
    /// @return A 401 Unauthorized response.
    @ExceptionHandler(TokenRevokedException.class)
    public ResponseEntity<ErrorResponse> handleTokenRevoked(TokenRevokedException ex, WebRequest request) {
        log.warn("Revoked token access attempt on path [{}]: {}",
                request.getDescription(false).replace("uri=", ""),
                ex.getMessage());

        return buildErrorResponse(
                HttpStatus.UNAUTHORIZED,
                "TOKEN_REVOKED",
                ex.getMessage(),
                request
        );
    }

    /// Catches generic 403 errors and Spring Security 6 specific authorization denials.
    ///
    /// This prevents the system from leaking internal configuration details when a
    /// security gate blocks a request.
    @ExceptionHandler({AccessDeniedException.class, AuthorizationDeniedException.class})
    public ResponseEntity<ErrorResponse> handleAccessDenied(Exception ex, WebRequest request) {
        log.warn("Security Gate Blocked: {} - {}", request.getDescription(false), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.FORBIDDEN,
                "Access Denied: You do not have permission to view this resource",
                request
        );
    }

    /// Handles instances where a requested URL path does not map to a physical resource or controller.
    /// This ensures that 404 errors return a standardized [ErrorResponse] instead of
    /// a generic internal failure.
    ///
    /// @param ex The [NoResourceFoundException] thrown when no static resource or handler is found.
    /// @param request The current web request used to extract the URI path.
    /// @return A 404 Not Found response containing sanitized error metadata.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(NoResourceFoundException ex, WebRequest request) {
        log.error("Resource not found on path [{}]: {}",
                request.getDescription(false).replace("uri=", ""),
                ex.getMessage());

        return buildErrorResponse(HttpStatus.NOT_FOUND, "Resource not found", request);
    }

    /// Handles domain validation errors (e.g., Unsupported Territories like "INR").
    ///
    /// @param ex The {@link IllegalArgumentException} thrown by domain services.
    /// @param request The web request context.
    /// @return A 400 Bad Request response with a standardized {@link ErrorResponse}.
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex, WebRequest request) {
        log.warn("Domain Validation Error: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /// Handles business state violations (e.g., "Wallet already exists" 1:1 rule).
    ///
    /// @param ex The {@link IllegalStateException} thrown when a state invariant is breached.
    /// @param request The web request context.
    /// @return A 409 Conflict response with a standardized {@link ErrorResponse}.
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex, WebRequest request) {
        log.warn("Business Logic Violation: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, ex.getMessage(), request);
    }


    /// Handles failures from external dependencies when a circuit breaker is OPEN or a service is down.
    ///
    /// This handler specifically intercepts [ServiceUnavailableException], which is typically thrown
    /// when the exchangeRateCircuitBreaker transitions to an OPEN state or the
    /// third-party ExchangeRate-API is unreachable.
    ///
    /// This aligns with the "Opaque" security principle by providing a consistent error structure
    /// without leaking internal stack traces or implementation details of the underlying dependency.
    ///
    /// @param ex The exception indicating a failure in an external service integration.
    /// @param request The current web request context for metadata extraction.
    /// @return A [ResponseEntity] containing a standardized JSON [ErrorResponse] with a 503 status code.
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleServiceUnavailable(ServiceUnavailableException ex, WebRequest request) {
        // Log the dependency failure at ERROR level for CloudWatch monitoring and alerting
        log.error("Dependency Failure: {}", ex.getMessage());

        // Return standardized JSON response to the frontend for consistent UI error handling
        return buildErrorResponse(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                ex.getMessage(),
                request
        );
    }


    /// Fallback handler for unexpected internal server errors.
    ///
    /// Provides full visibility in **AWS CloudWatch** through detailed error logging while
    /// returning a sanitized response to the client.
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

    /// Convenience Helper: Uses the HTTP Status Name as the Error Code (e.g., 404 -> "NOT_FOUND").
    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status,
            String message,
            WebRequest request
    ) {
        return buildErrorResponse(status, status.name(), message, request);
    }

    /// Master Error Factory: Builds a consistent {@link ErrorResponse} DTO.
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