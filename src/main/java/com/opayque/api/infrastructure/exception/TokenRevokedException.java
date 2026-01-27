package com.opayque.api.infrastructure.exception;

import org.springframework.security.core.AuthenticationException;

/// Epic 1: Identity & Access Management - Token Revocation Exception
///
/// Specialized authentication exception thrown when a user attempts to access the API
/// with a JWT whose signature has been explicitly blacklisted in Redis.
/// This is a key component of the **Story 1.4: Kill Switch** logic.
public class TokenRevokedException extends AuthenticationException {

  /// Constructs a new exception with the specific revocation message.
  /// @param message Detailed description of the revocation event.
  public TokenRevokedException(String message) {
    super(message);
  }
}