package com.opayque.api.infrastructure.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Epic 1: Identity & Access Management - Infrastructure Exceptions
 * * Thrown when a requested user identity cannot be located in the PostgreSQL ledger.
 * This exception is specifically utilized during login flows and administrative
 * actions (like account freezing) to ensure system integrity.
 * * Design Note:
 * Uses @ResponseStatus to provide a default HTTP 404 mapping, though it is
 * typically intercepted by the GlobalExceptionHandler for custom JSON formatting.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class UserNotFoundException extends RuntimeException {

  /// Constructs a new exception with a specific detail message.
  /// * @param message is The detail message explaining which identity search failed.
  public UserNotFoundException(String message) {
    super(message);
  }
}