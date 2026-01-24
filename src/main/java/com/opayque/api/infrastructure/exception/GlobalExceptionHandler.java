package com.opayque.api.infrastructure.exception;


import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Epic 1: Identity & Access Management - Infrastructure Layer
 * * This global interceptor catches exceptions thrown across all controllers
 * to provide a standardized, client-friendly error response format.
 * * Design Philosophy:
 * - Consistency: All validation errors return a 400 Bad Request with field-specific messages.
 * - Security: Custom exceptions like UserAlreadyExistsException are mapped to specific
 * HTTP status codes (409 Conflict) to avoid leaking internal stack traces.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handles business logic conflicts, specifically when a user attempts to register
     * with an email that is already persisted in the ledger.
     *
     * @param ex The custom UserAlreadyExistsException.
     * @return A 409 Conflict response with the specific error message.
     */
    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<String> handleUserExists(UserAlreadyExistsException ex) {
        log.debug("Conflict detected: Registration attempt with existing email. Reason: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
    }

    /**
     * Handles Bean Validation failures triggered by the @Valid annotation on
     * the RegisterRequest DTO.
     *
     * @param ex Exception thrown when argument validation fails.
     * @return A 400 Bad Request containing a map of field names and their corresponding error messages.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        log.warn("Validation failed for incoming request. Error count: {}", ex.getBindingResult().getErrorCount());

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(errors);
    }

    /**
     * Fallback handler for unexpected internal server errors.
     * Ensures that sensitive stack traces are not leaked to the client while providing full visibility in logs.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleGeneralException(Exception ex) {
        log.error("CRITICAL: Unexpected system failure encountered", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("An internal service error occurred.");
    }
}