package com.opayque.api.infrastructure.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/// Configuration for external financial service integrations and fault tolerance mechanisms.
///
/// This class orchestrates the connectivity to third-party providers (e.g., ExchangeRate-API)
/// while enforcing architectural guardrails to prevent cascading failures in the banking core.
/// It adheres to the project's "Reliability-First" mandate by providing a hardened integration layer.
@Configuration
@EnableCaching
public class IntegrationConfig {

    /**
     * The base URL for the external currency exchange rate provider.
     * Injected from application properties to support environment-specific overrides (Dev/Prod).
     */
    @Value("${application.integration.exchange-rate.base-url}")
    private String exchangeRateBaseUrl;

    /// Configures the modern, synchronous [RestClient] for currency conversion lookups.
    ///
    /// Choice of [RestClient] over WebClient reflects the project's preference for synchronous simplicity
    /// over reactive complexity for this specific domain logic.
    ///
    /// @param builder The Spring-managed [RestClient.Builder] instance.
    /// @return A configured [RestClient] pointing to the ExchangeRate-API gateway.
    @Bean
    public RestClient exchangeRateClient(RestClient.Builder builder) {
        return builder.baseUrl(exchangeRateBaseUrl).build();
    }

    /// Explicit configuration of a Circuit Breaker for the Exchange Rate integration.
    ///
    /// This implementation avoids "AOP magic" (Aspect-Oriented Programming) in alignment with
    /// the project's skill inventory exclusions, ensuring explicit control over the breaker's lifecycle.
    ///
    /// The configuration utilizes the following parameters to protect the ExchangeRate-API free-tier limits (1500 req/month):
    /// - **Failure Threshold (50%)**: Transitions to OPEN state if half of the sampled requests fail.
    /// - **Wait Duration (1s)**: The cooldown period before attempting a HALF-OPEN transition.
    /// - **Sliding Window (2)**: A minimal window optimized for high-precision testing and immediate response to rate-limiting.
    ///
    /// @return A named [CircuitBreaker] instance for the `exchangeRateService`.
    @Bean
    public CircuitBreaker exchangeRateCircuitBreaker() {
        // Define architectural rules for fault tolerance
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50) // Open circuit if 50% failure rate is detected
                .waitDurationInOpenState(Duration.ofMillis(1000)) // 1 second "cooling-off" period
                .slidingWindowSize(2) // Aggressive windowing to handle limited external API quotas
                .build();

        // Registry acting as the central management point for external service resilience state
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        return registry.circuitBreaker("exchangeRateService");
    }
}