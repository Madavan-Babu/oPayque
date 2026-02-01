package com.opayque.api.integration.currency;

import com.opayque.api.infrastructure.exception.ServiceUnavailableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.HttpHeaders.CONTENT_TYPE;
import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "application.integration.exchange-rate.base-url=http://localhost:${wiremock.server.port}/v6",
                "application.integration.exchange-rate.api-key=test-dummy-key"
        }
)
@Testcontainers
@AutoConfigureWireMock(port = 0) // Random port for WireMock
class ExchangeRateIntegrationTest {

    // --- Infrastructure: Real Redis via Testcontainers ---
    @SuppressWarnings("resource")
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    @Autowired
    private CurrencyExchangeService exchangeService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @MockitoSpyBean
    private CircuitBreaker exchangeRateCircuitBreaker;

    @BeforeEach
    void setUp() {
        // Clear Redis before each test
        if (redisTemplate.getConnectionFactory() != null) {
            redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        }

        // Reset WireMock
        reset();

        // FIX: Force Circuit Breaker to CLOSED (Healthy) before every test
        exchangeRateCircuitBreaker.transitionToClosedState();
    }

    // --- EXISTING TESTS (PRESERVED) ---

    @Test
    @DisplayName("Resilience: Should fetch from API on first call and Cache the result")
    void shouldFetchAndCacheRates() {
        stubFor(get(urlPathMatching("/v6/test-dummy-key/latest/USD"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("""
                            {
                                "result": "success",
                                "base_code": "USD",
                                "conversion_rates": { "EUR": 0.85, "GBP": 0.75 }
                            }
                        """)));

        BigDecimal rate1 = exchangeService.getRate("USD", "EUR");
        assertThat(rate1).isEqualByComparingTo("0.85");
        verify(1, getRequestedFor(urlPathMatching("/v6/test-dummy-key/latest/USD")));

        BigDecimal rate2 = exchangeService.getRate("USD", "EUR");
        assertThat(rate2).isEqualByComparingTo("0.85");
        verify(1, getRequestedFor(urlPathMatching("/v6/test-dummy-key/latest/USD")));
    }

    @Test
    @DisplayName("Cache Policy: Should ignore stale cache and refetch from API")
    void shouldRefreshStaleCache() throws InterruptedException {
        stubFor(get(urlPathMatching("/v6/test-dummy-key/latest/USD"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("""
                            {
                                "result": "success",
                                "base_code": "USD",
                                "conversion_rates": { "EUR": 0.90 } 
                            }
                        """)));

        String cacheKey = "exchange_rates::USD-EUR";
        redisTemplate.opsForValue().set(cacheKey, new BigDecimal("0.85"), Duration.ofMillis(1000));

        Thread.sleep(1100);

        BigDecimal rate = exchangeService.getRate("USD", "EUR");
        assertThat(rate).isEqualByComparingTo("0.90");
        verify(1, getRequestedFor(urlPathMatching("/v6/test-dummy-key/latest/USD")));
    }

    @Test
    @DisplayName("Circuit Breaker: Should throw ServiceUnavailableException on External 500")
    void shouldHandleExternalFailure() {
        stubFor(get(urlPathMatching("/v6/test-dummy-key/latest/USD"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> exchangeService.getRate("USD", "EUR"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("External exchange rate API unavailable");
    }

    // --- NEW TESTS (ADDED FOR 100% COVERAGE) ---

    @Test
    @DisplayName("Logic: Should return 1.0 immediately for same-currency requests")
    void shouldReturnOneForSameCurrency() {
        // Act
        BigDecimal rate = exchangeService.getRate("USD", "USD");

        // Assert
        assertThat(rate).isEqualByComparingTo("1.0");
        // Verify we didn't touch the network
        verify(0, getRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Logic: Should throw Exception if API returns 'error' status")
    void shouldHandleApiLogicalError() {
        // Arrange: API returns 200 OK, but business status is "error"
        stubFor(get(urlPathMatching("/v6/test-dummy-key/latest/USD"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("""
                            {
                                "result": "error",
                                "error-type": "invalid-key"
                            }
                        """)));

        // Act & Assert
        assertThatThrownBy(() -> exchangeService.getRate("USD", "EUR"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("External exchange provider returned invalid response");
    }

    @Test
    @DisplayName("Logic: Should throw Exception if Target Currency is missing")
    void shouldHandleMissingCurrency() {
        // Arrange: API success, but EUR is missing
        stubFor(get(urlPathMatching("/v6/test-dummy-key/latest/USD"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader(CONTENT_TYPE, APPLICATION_JSON_VALUE)
                        .withBody("""
                            {
                                "result": "success",
                                "conversion_rates": { "GBP": 0.75 } 
                            }
                        """)));

        // Act & Assert
        assertThatThrownBy(() -> exchangeService.getRate("USD", "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported target currency: EUR");
    }

    @Test
    @DisplayName("Resilience: Should Handle Open Circuit Breaker")
    void shouldHandleCircuitBreakerOpen() {
        // 1. Force Circuit Breaker OPEN
        exchangeRateCircuitBreaker.transitionToOpenState();

        // 2. Expect Custom Exception
        assertThatThrownBy(() -> exchangeService.getRate("USD", "EUR"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Currency Exchange is temporarily blocked");

        // Cleanup handled by @BeforeEach
    }

    @Test
    @DisplayName("Resilience: Should Handle Timeout")
    void shouldHandleTimeout() {
        // 1. Stub API with a delay longer than default timeout (e.g., 5s delay)
        stubFor(get(urlPathMatching("/v6/test-dummy-key/latest/USD"))
                .willReturn(aResponse()
                        .withFixedDelay(5000) // 5 seconds delay
                        .withStatus(200)));

        // 2. Act & Assert
        // Note: RestClient usually throws ResourceAccessException on timeout,
        // which your fallback wraps as "External exchange rate API unavailable".
        // If TimeLimiter is active, you might get the "Currency Exchange timed out" message.
        // We verify generally that it fails gracefully.
        assertThatThrownBy(() -> exchangeService.getRate("USD", "EUR"))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    // --- NEW TESTS (Surgical Coverage) ---

    @Test
    @DisplayName("Resilience: Should Handle Raw TimeoutException (Sneaky Throw)")
    void shouldHandleRawTimeoutException() {
        // FIX: Force the spy to throw a Checked Exception (TimeoutException) that isn't declared.
        // This is the only way to hit "if (t instanceof TimeoutException)" because
        // the standard Java compiler normally prevents a Supplier from throwing this.
        doAnswer(invocation -> {
            sneakyThrow(new TimeoutException("Forced Timeout"));
            return null;
        }).when(exchangeRateCircuitBreaker).acquirePermission();

        assertThatThrownBy(() -> exchangeService.getRate("USD", "EUR"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("Currency Exchange timed out.");
    }

    @Test
    @DisplayName("Resilience: Should Handle Unexpected Runtime Errors (Catch-All)")
    void shouldHandleUnexpectedError() {
        // Force an unexpected RuntimeException via the Spy
        // This bypasses the API logic and hits the 'catch (Throwable)' -> 'fallbackRate' -> Catch-All Log
        doThrow(new RuntimeException("Chaos Monkey")).when(exchangeRateCircuitBreaker).acquirePermission();

        assertThatThrownBy(() -> exchangeService.getRate("USD", "EUR"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("Currency Exchange unavailable: Chaos Monkey");
    }

    @Test
    @DisplayName("Logic: Should Propagate Validation Errors in Fallback")
    void shouldHandleValidationFailureInFallback() {
        // Force an IllegalArgumentException via the Spy to guarantee the 'instanceof' check is covered
        doThrow(new IllegalArgumentException("Forced Validation Error")).when(exchangeRateCircuitBreaker).acquirePermission();

        assertThatThrownBy(() -> exchangeService.getRate("USD", "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forced Validation Error");
    }

    // --- MAGIC HELPER ---
    // Allows throwing checked exceptions (like TimeoutException) without declaring them.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
}