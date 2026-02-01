package com.opayque.api.integration.currency;

import com.opayque.api.infrastructure.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class CurrencyExchangeService {

    private final RestClient exchangeRateClient;
    private final CircuitBreaker exchangeRateCircuitBreaker;

    @Value("${application.integration.exchange-rate.api-key}")
    private String apiKey;

    @Cacheable(value = "exchange_rates", key = "#from + '-' + #to")
    public BigDecimal getRate(String from, String to) {
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }

        // 1. Wrap the API call with the Circuit Breaker logic
        Supplier<BigDecimal> decoratedSupplier = CircuitBreaker.decorateSupplier(
                exchangeRateCircuitBreaker,
                () -> fetchFromApi(from, to)
        );

        // 2. Execute with Manual Fallback (Try-Catch is cleaner than Decorators here)
        try {
            return decoratedSupplier.get();
        } catch (Throwable t) {
            // This catches BOTH CircuitBreakerOpenException AND API Failures
            return fallbackRate(from, to, t);
        }
    }

    private BigDecimal fetchFromApi(String from, String to) {
        log.debug("Cache Miss: Fetching live exchange rate for {} -> {}", from, to);
        try {
            ExchangeRateResponse response = exchangeRateClient.get()
                    .uri("/" + apiKey + "/latest/" + from)
                    .retrieve()
                    .body(ExchangeRateResponse.class);

            if (response == null || !"success".equals(response.result())) {
                throw new ServiceUnavailableException("External exchange provider returned invalid response.");
            }

            BigDecimal rate = response.conversionRates().get(to);
            if (rate == null) {
                throw new IllegalArgumentException("Unsupported target currency: " + to);
            }
            return rate;
        } catch (RestClientException ex) {
            log.error("External API Failure: {}", ex.getMessage());
            throw new ServiceUnavailableException("External exchange rate API unavailable");
        }
    }

    private BigDecimal fallbackRate(String from, String to, Throwable t) {
        if (t instanceof CallNotPermittedException) {
            log.warn("Circuit Breaker OPEN: Blocking request for {} -> {}", from, to);
            throw new ServiceUnavailableException("Currency Exchange is temporarily blocked due to high error rates.");
        }
        if (t instanceof TimeoutException) {
            throw new ServiceUnavailableException("Currency Exchange timed out.");
        }

        // 3. FIX: Propagate existing ServiceUnavailableException (Don't swallow the specific message!)
        // This allows "External exchange rate API unavailable" to pass through to the test.
        if (t instanceof ServiceUnavailableException) {
            throw (ServiceUnavailableException) t;
        }

        // 4. NEW FIX: Propagate Logic/Validation Errors (Don't wrap them in 503)
        // If the currency is unsupported (IllegalArgumentException), we must throw that exact
        // exception so the Test (and the GlobalExceptionHandler) handles it as a 400 Bad Request.
        if (t instanceof IllegalArgumentException) {
            throw (IllegalArgumentException) t;
        }

        log.error("Fallback triggered for {} -> {}. Reason: {}", from, to, t.getMessage());
        // FIX: Include the original cause in the message for easier debugging
        throw new ServiceUnavailableException("Currency Exchange unavailable: " + t.getMessage());
    }
}