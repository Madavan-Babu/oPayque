package com.opayque.api.integration.currency;

import com.opayque.api.infrastructure.exception.ServiceUnavailableException;
import com.opayque.api.integration.currency.dto.ExchangeRateResponse;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/// Orchestrator for currency conversion logic and external API communication.
///
/// This service serves as the gateway to the **ExchangeRate-API**, integrating live market data
/// with the **oPayque** transaction engine. It implements a layered resilience strategy
/// involving sub-millisecond local caching, explicit circuit breaking, and rigorous error propagation
/// to ensure the "High-Precision" mandate of the banking core.
///
/// Key Architectural Features:
/// - **Resilience**: Integrated with [CircuitBreaker] to prevent cascading failures.
/// - **Performance**: Utilizes [Cacheable] to minimize external latency and preserve API quotas.
/// - **Type Safety**: Enforces [BigDecimal] for all financial calculations to prevent floating-point errors.
@Service
@RequiredArgsConstructor
@Slf4j
public class CurrencyExchangeService {

    private final RestClient exchangeRateClient;
    private final CircuitBreaker exchangeRateCircuitBreaker;

    /**
     * Secret key for the ExchangeRate-API provider.
     * Injected via environment variables (e.g., GitHub Secrets in CI/CD).
     */
    @Value("${application.integration.exchange-rate.api-key}")
    private String apiKey;

    /// Retrieves the exchange rate between two currencies with caching and circuit breaking.
    ///
    /// The workflow follows:
    /// 1. Identity Check: Returns 1.0 if currencies are identical.
    /// 2. Cache Lookup: Checks Redis/Local cache for existing rate pairs.
    /// 3. Circuit Breaker Execution: Wraps the API call to monitor health thresholds.
    /// 4. Fallback Logic: Handles system outages or rate limits gracefully.
    ///
    /// @param from The source ISO 4217 currency code.
    /// @param to The target ISO 4217 currency code.
    /// @return The conversion rate as a [BigDecimal].
    /// @throws ServiceUnavailableException If the circuit is open or external provider is down.
    /// @throws IllegalArgumentException If an unsupported currency is requested.
    @Timed(value = "opayque.exchange.api.latency", description = "Early warning tracker for third-party Forex provider delays")
    @Cacheable(value = "exchange_rates", key = "#from + '-' + #to")
    public BigDecimal getRate(String from, String to) {
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }

        // 1. Wrap the API call with the Circuit Breaker logic
        // We use a functional Supplier to allow the CircuitBreaker to intercept the call lifecycle.
        Supplier<BigDecimal> decoratedSupplier = CircuitBreaker.decorateSupplier(
                exchangeRateCircuitBreaker,
                () -> fetchFromApi(from, to)
        );

        // 2. Execute with Manual Fallback (Try-Catch is cleaner than Decorators here)
        // Manual handling ensures we distinguish between structural failures and business validation errors.
        try {
            return decoratedSupplier.get();
        } catch (Throwable t) {
            // This catches BOTH CallNotPermittedException (Circuit Open) AND API Failures
            return fallbackRate(from, to, t);
        }
    }

    /// Performs the actual HTTP request to the external rate provider.
    ///
    /// This method is only invoked on a cache miss. It validates the API response
    /// structure to ensure data integrity before passing it to the ledger engine.
    ///
    /// @param from Source currency.
    /// @param to Target currency.
    /// @return Validated exchange rate.
    /// @throws ServiceUnavailableException If the external response is malformed or the API is unreachable.
    private BigDecimal fetchFromApi(String from, String to) {
        log.debug("Cache Miss: Fetching live exchange rate for {} -> {}", from, to);
        try {
            ExchangeRateResponse response = exchangeRateClient.get()
                    .uri("/" + apiKey + "/latest/" + from)
                    .retrieve()
                    .body(ExchangeRateResponse.class);

            // Validation of API-specific 'success' status
            if (response == null || !"success".equals(response.result())) {
                throw new ServiceUnavailableException("External exchange provider returned invalid response.");
            }

            BigDecimal rate = response.conversionRates().get(to);
            if (rate == null) {
                // Thrown if the 'to' currency does not exist in the provider's dataset
                throw new IllegalArgumentException("Unsupported target currency: " + to);
            }
            return rate;
        } catch (RestClientException ex) {
            log.error("External API Failure: {}", ex.getMessage());
            throw new ServiceUnavailableException("External exchange rate API unavailable");
        }
    }

    /// Standardized fallback handler for integration failures.
    ///
    /// This method acts as a traffic controller for exceptions, ensuring that
    /// 503 (Service Unavailable) is used for system issues and 400 (Bad Request)
    /// is used for invalid input, preventing the swallow of critical validation errors.
    ///
    /// @param from Source currency context.
    /// @param to Target currency context.
    /// @param t The caught exception.
    /// @return Never returns; always throws a refined exception.
    private BigDecimal fallbackRate(String from, String to, Throwable t) {
        // Handle Resilience4j Circuit Breaker 'OPEN' state
        if (t instanceof CallNotPermittedException) {
            log.warn("Circuit Breaker OPEN: Blocking request for {} -> {}", from, to);
            throw new ServiceUnavailableException("Currency Exchange is temporarily blocked due to high error rates.");
        }

        // Handle Request Timeouts
        if (t instanceof TimeoutException) {
            throw new ServiceUnavailableException("Currency Exchange timed out.");
        }

        // 3. FIX: Propagate existing ServiceUnavailableException (Don't swallow the specific message!)
        // Preserves the detailed error context from fetchFromApi for the GlobalExceptionHandler.
        if (t instanceof ServiceUnavailableException) {
            throw (ServiceUnavailableException) t;
        }

        // 4. NEW FIX: Propagate Logic/Validation Errors (Don't wrap them in 503)
        // Critical for ensuring the API remains REST-compliant by returning 4xx for bad inputs.
        if (t instanceof IllegalArgumentException) {
            throw (IllegalArgumentException) t;
        }

        log.error("Fallback triggered for {} -> {}. Reason: {}", from, to, t.getMessage());

        // Generic catch-all for unexpected integration errors
        throw new ServiceUnavailableException("Currency Exchange unavailable: " + t.getMessage());
    }
}