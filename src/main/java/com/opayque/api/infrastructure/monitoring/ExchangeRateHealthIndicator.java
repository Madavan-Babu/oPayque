package com.opayque.api.infrastructure.monitoring;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

/**
 * <p>
 * Provides a Spring {@link HealthIndicator} that monitors the availability of the external
 * ExchangeRate API used throughout the application. The indicator performs a lightweight
 * request to the provider’s {@code latest/EUR} endpoint and reports {@code UP} when a
 * {@code 2xx} response is received, otherwise {@code DOWN} with diagnostic details.
 * </p>
 *
 * <p>
 * The health check is essential for preventing downstream services from operating with
 * stale or missing foreign‑exchange data, which could lead to inaccurate calculations or
 * regulatory compliance issues. By surfacing the provider’s status in the Actuator health
 * endpoint, operations teams can quickly react to outages and trigger fallback mechanisms.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see com.opayque.api.integration.currency.CurrencyExchangeService
 */
@Component
@Slf4j
public class ExchangeRateHealthIndicator implements HealthIndicator {

    private final RestClient restClient;
    private final String baseUrl;
    private final String apiKey;

    public ExchangeRateHealthIndicator(
            RestClient.Builder restClientBuilder,
            @Value("${application.integration.exchange-rate.base-url}") String baseUrl,
            @Value("${application.integration.exchange-rate.api-key}") String apiKey) {

        // Configure the modern RequestFactory with strict 3-second timeouts
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofSeconds(3));

        this.restClient = restClientBuilder
                .requestFactory(requestFactory)
                .build();
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    /**
     * Performs a health check against the external ExchangeRate API to verify that the
     * service providing EUR exchange rates is reachable and operational.
     * <p>
     * The method constructs the request URL using the configured {@code baseUrl} and
     * {@code apiKey}, then issues a lightweight GET request via {@link RestClient}
     * expecting a 2xx HTTP response.  A successful response yields an {@link Health}
     * instance marked {@code UP} with details describing the service name,
     * its reachable status, and the currency that was validated.
     * <p>
     * In the event of a {@link RestClientException}, the health status is reported as
     * {@code DOWN} and includes an error message together with a remediation hint,
     * guiding operators to verify the API key and the external provider's status page.
     * Any other unexpected exception is also caught, logged, and results in a
     * {@code DOWN} status where the exception details are propagated in the health
     * report to avoid terminating the health‑check thread.
     *
     * @return a {@link Health} object representing the current availability of the
     *         external ExchangeRate service, enriched with diagnostic details.
     *
     * @see HealthIndicator
     */
    @Override
    public Health health() {
        // Strictly adhering to IBAN jurisdiction: Using EUR for the availability ping
        String url = String.format("%s/%s/latest/EUR", baseUrl, apiKey);

        try {
            // We use a lightweight retrieve() to check provider status.
            // We only care if it responds with a 2xx status code.
            restClient.get()
                    .uri(url)
                    .retrieve()
                    .toBodilessEntity();

            return Health.up()
                    .withDetail("service", "ExchangeRate-API")
                    .withDetail("status", "Reachable")
                    .withDetail("currency_check", "EUR")
                    .build();
        } catch (RestClientException e) {
            log.error("Critical Failure: Forex Provider is unreachable. Root cause: {}", e.getMessage());
            return Health.down()
                    .withDetail("service", "ExchangeRate-API")
                    .withDetail("error", e.getMessage())
                    .withDetail("remediation", "Check API Key and external provider status page")
                    .build();
        } catch (Exception e) {
            // General catch-all to ensure the health-check thread itself never crashes the application
            log.error("Unexpected error during ExchangeRate health check", e);
            return Health.down(e).build();
        }
    }
}