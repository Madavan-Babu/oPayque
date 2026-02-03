package com.opayque.api.infrastructure.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/// Exception indicating that a required external service or system component is currently unreachable or non-functional.
///
/// In the context of the **oPayque** architecture, this exception is primarily utilized by the integration layer
/// (Epic 7) to signal failures in downstream dependencies, such as the **ExchangeRate-API**.
/// It acts as a failure signal for the `exchangeRateCircuitBreaker` when health thresholds are breached.
///
/// This exception maps directly to an **HTTP 503 Service Unavailable** status, informing the client (e.g., React or
/// Flutter frontend) that the request cannot be fulfilled due to a temporary overload or maintenance of a
/// third-party system.
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class ServiceUnavailableException extends RuntimeException {

  /// Constructs a new [ServiceUnavailableException] with a specific error message.
  ///
  /// This message is captured by the [GlobalExceptionHandler] and returned in a consistent
  /// JSON format to ensure high-precision error reporting.
  ///
  /// @param message A descriptive string explaining which dependency failed (e.g., "ExchangeRate-API is unreachable").
  public ServiceUnavailableException(String message) {
    super(message);
  }
}