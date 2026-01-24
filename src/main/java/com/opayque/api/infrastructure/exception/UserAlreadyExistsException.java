package com.opayque.api.infrastructure.exception;


/**
 * Epic 1: Identity & Access Management - Infrastructure Exceptions
 * * Custom runtime exception thrown when an identity collision occurs during the registration process.
 * This is intercepted by the {@link GlobalExceptionHandler} to return a 409-Conflict status.
 */
public class UserAlreadyExistsException extends RuntimeException {

  /**
   * Constructs a new exception with a specific conflict message.
   * * @param message The detail message explaining the identity conflict.
   */
  public UserAlreadyExistsException(String message) {
    super(message);
  }
}