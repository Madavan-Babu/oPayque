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

@Configuration
@EnableCaching
public class IntegrationConfig {

    @Value("${application.integration.exchange-rate.base-url}")
    private String exchangeRateBaseUrl;

    /// Configures the modern synchronous HTTP Client.
    @Bean
    public RestClient exchangeRateClient(RestClient.Builder builder) {
        return builder.baseUrl(exchangeRateBaseUrl).build();
    }

    /// MANUAL CIRCUIT BREAKER CONFIGURATION
    /// Instead of AOP magic, we define the specific breaker bean here.
    @Bean
    public CircuitBreaker exchangeRateCircuitBreaker() {
        // Define the rules
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50) // Open if 50% of requests fail
                .waitDurationInOpenState(Duration.ofMillis(1000)) // Wait 1s before trying again
                .slidingWindowSize(2) // Look at the last 2 requests (Small for testing/Free tier)
                .build();

        // Create the registry and the breaker
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        return registry.circuitBreaker("exchangeRateService");
    }
}