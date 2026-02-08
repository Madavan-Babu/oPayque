package com.opayque.api.infrastructure.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * A <strong>security-hardened, FinTech-grade</strong> exception thrown when an API consumer
 * exhausts the allocated request quota within the sliding or fixed time window enforced by the
 * Token-Bucket algorithm.
 *
 * <p>In the context of a regulated financial platform, this exception acts as a <em>dynamic
 * circuit-breaker</em> that
 *
 * <ul>
 *   <li>mitigates brute-force attacks against payment endpoints,
 *   <li>prevents budget-draining micro-transaction loops,
 *   <li>ensures fair-usage compliance under PCI-DSS, PSD2 &amp; local CBIRC rules,
 *   <li>protects high-value ledger operations from noisy-neighbor interference.
 * </ul>
 *
 * <p>The exception is automatically mapped to HTTP 429 <em>Too Many Requests</em> by Spring's
 * {@link ResponseStatus @ResponseStatus} annotation, signalling to both client-side SDKs and
 * intermediate gateways (e.g., API-gateway, WAF, Istio Envoy) that the caller must
 * <strong>back-off</strong> and retry after the {@code Retry-After}
 *
 * <p>Thread-safe and immutable once constructed; carries no sensitive PII or card-holder data,
 * ensuring full alignment with data-sovereignty and audit-trail requirements.
 *
 * @author Madavan Babu
 * @since 2.0.0
 * @see com.opayque.api.infrastructure.ratelimit.RateLimiterService
 */
@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
public class RateLimitExceededException extends RuntimeException {
  public RateLimitExceededException(String message) {
    super(message);
  }
}