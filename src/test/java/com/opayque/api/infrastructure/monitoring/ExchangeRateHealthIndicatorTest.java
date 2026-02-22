package com.opayque.api.infrastructure.monitoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/**
 * Unit test suite for {@link ExchangeRateHealthIndicator}.
 * <p>
 * This class achieves 100% branch and line coverage for the custom health actuator.
 * It uses Mockito to intercept the fluent {@link RestClient} builder and request chains,
 * ensuring fast execution without loading the Spring Application Context.
 *
 * @author Madavan Babu
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
@ActiveProfiles("test")
class ExchangeRateHealthIndicatorTest {

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private RestClient restClient;

    // Suppressing generic warnings for the fluent Mockito chain
    @Mock
    @SuppressWarnings("rawtypes")
    private RestClient.RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    @SuppressWarnings("rawtypes")
    private RestClient.RequestHeadersSpec requestHeadersSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    private ExchangeRateHealthIndicator healthIndicator;

    private final String BASE_URL = "https://api.exchangerate-api.com/v4";
    private final String API_KEY = "test-secret-key";

    @BeforeEach
    void setUp() {
        // 1. Intercept the Builder inside the constructor
        // The constructor overwrites the requestFactory, so we must return the builder itself
        when(restClientBuilder.requestFactory(any())).thenReturn(restClientBuilder);
        when(restClientBuilder.build()).thenReturn(restClient);

        // 2. Instantiate the target
        healthIndicator = new ExchangeRateHealthIndicator(restClientBuilder, BASE_URL, API_KEY);
    }

    @Test
    @DisplayName("Branch 1 (Try): Should return UP when Forex Provider responds with 2xx")
    void health_WhenProviderIsReachable_ReturnsUp() {
        // Arrange
        String expectedUrl = BASE_URL + "/" + API_KEY + "/latest/EUR";

        // Using doReturn to safely mock the generic fluent chain
        doReturn(requestHeadersUriSpec).when(restClient).get();
        doReturn(requestHeadersSpec).when(requestHeadersUriSpec).uri(expectedUrl);
        doReturn(responseSpec).when(requestHeadersSpec).retrieve();
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        // Act
        Health health = healthIndicator.health();

        // Assert
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("service", "ExchangeRate-API")
                .containsEntry("status", "Reachable")
                .containsEntry("currency_check", "EUR");
    }

    @Test
    @DisplayName("Branch 2 (Catch 1): Should return DOWN with remediation when RestClientException is thrown")
    void health_WhenProviderThrowsRestClientException_ReturnsDown() {
        // Arrange
        doReturn(requestHeadersUriSpec).when(restClient).get();
        doReturn(requestHeadersSpec).when(requestHeadersUriSpec).uri(anyString());

        // Simulate a timeout or 4xx/5xx from the provider
        when(requestHeadersSpec.retrieve()).thenThrow(new RestClientException("Connection Timed Out"));

        // Act
        Health health = healthIndicator.health();

        // Assert
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("service", "ExchangeRate-API")
                .containsEntry("error", "Connection Timed Out")
                .containsEntry("remediation", "Check API Key and external provider status page");
    }

    @Test
    @DisplayName("Branch 3 (Catch 2): Should return DOWN when an unexpected Exception occurs")
    void health_WhenUnexpectedExceptionOccurs_ReturnsDown() {
        // Arrange
        // Simulate a complete internal failure (e.g., null pointer) before the network call
        when(restClient.get()).thenThrow(new NullPointerException("Unexpected JVM Error"));

        // Act
        Health health = healthIndicator.health();

        // Assert
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);

        // FIXED: Access the exception information through the details map
        // Spring's Health.down(e) automatically places the exception/message in the details map
        assertThat(health.getDetails()).containsKey("error");
        assertThat(health.getDetails().get("error").toString()).contains("Unexpected JVM Error");
    }
}