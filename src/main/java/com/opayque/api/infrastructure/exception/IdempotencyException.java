package com.opayque.api.infrastructure.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Indicates that the Idempotency Engine has rejected a request because an operationally identical
 * transaction—identified by the idempotency-key header— has already been accepted within the
 * retention window.
 *
 * <p>In a FinTech context this exception is a critical control against duplicate debits, double
 * spending, and replay attacks. The engine compares the normalized payload, HTTP method, URI, and
 * authenticated principal to ensure semantic equivalence before allowing re-execution.
 *
 * <p>The mapped HTTP 409 Conflict status signals to API consumers that the resource state (e.g.,
 * ledger balance, payment status, or settlement position) is already consistent with the intended
 * effect; clients should <strong>not</strong> retry with a different key unless they intend to
 * create a new transaction.
 *
 * <p>Security note: Returning 409 instead of 200 prevents leakage of the original response body to
 * unauthorized callers that might possess only the idempotency key.
 *
 * @since 2.0.0
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class IdempotencyException extends RuntimeException {
  public IdempotencyException(String message) {
    super(message);
  }
}