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
import org.springframework.test.context.ActiveProfiles;
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

/// Integration tests for the exchange rate functionality, verifying the resilience,
/// caching behavior, circuit breaker handling, and data validity of the system.
/// This class uses Redis as a caching layer and mocks an external API through WireMock.
/// Configuration:
/// - The tests run within a Spring Boot context, using a random web environment port.
/// - Redis is initialized as a Testcontainer to simulate a real caching infrastructure.
/// - WireMock is used to stub external API responses and simulate various conditions.
/// - All tests run with an active "test" profile to isolate the test environment.
/// Key Features Tested:
/// 1. Resilience: Verifying caching and proper handling of API responses, including retries.
/// 2. Circuit Breaker: Ensuring graceful degradation when the external API is unreachable.
/// 3. Logical Errors: Validating system behavior for various input and API response constraints.
/// 4. Timeout Handling: Testing the system's reaction to API delays exceeding permissible limits.
/// 5. Cache Policy: Checking the validity and expiration of cached exchange rate data.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "application.integration.exchange-rate.base-url=http://localhost:${wiremock.server.port}/v6",
                "application.integration.exchange-rate.api-key=test-dummy-key"
        }
)
@Testcontainers
@AutoConfigureWireMock(port = 0) // Random port for WireMock
@ActiveProfiles("test")
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

    /// Prepares the test environment before each test execution.
    /// This method ensures a clean state for Redis, resets any mock server configurations,
    /// and ensures that the circuit breaker is in a healthy (closed) state at the start of every test.
    /// Actions performed:
    /// - Clears all data in the Redis database using the `redisTemplate` to flush all keys.
    /// - Resets the WireMock server to its default state.
    /// - Forces the `exchangeRateCircuitBreaker` to transition to a closed state.
    /// Annotated with `@BeforeEach`, this method runs before every test method in the test class.
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

    /// Tests the caching and fetching behavior of the exchange rate service under normal conditions.
    /// The method validates the following:
    /// 1. On the first invocation, the service fetches the exchange rate from the external API
    ///    and caches the result.
    /// 2. On subsequent invocations for the same currency pair, the service retrieves the rate from the cache
    ///    without making a redundant API call.
    /// Test Workflow:
    /// - Stubs the external API response for a given base currency (USD) and target conversion rates.
    /// - Uses the `exchangeService.getRate` method to fetch the exchange rate for USD to EUR.
    /// - Verifies that the first call results in one API request and stores the response.
    /// - Verifies that subsequent calls retrieve the rate directly from the cache, avoiding extra API requests.
    /// Expectations:
    /// - The API is called exactly once for a specific currency pair throughout the test lifecycle.
    /// - The cached rate matches the value returned from the API on the first call.
    /// This test ensures that the caching layer in the exchange service is functioning as intended
    /// and that unnecessary API calls are avoided, optimizing performance and resilience.
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

    /// Tests the behavior of the exchange rate service when dealing with stale cache entries.
    /// This test validates the service's ability to ignore stale cached data and retrieve
    /// fresh rates from the external API when the cache expiration time has passed.
    /// Test Workflow:
    /// - Sets up a mocked API response for the specified base currency (USD) and target rates.
    /// - Adds a stale exchange rate (0.85) for USD to EUR into the Redis cache with a short TTL (Time To Live).
    /// - Ensures the test waits for the cache entry to expire by introducing a delay.
    /// - Invokes the `exchangeService.getRate` method to fetch the exchange rate.
    /// Expectations:
    /// - The call to `exchangeService.getRate` bypasses the stale cache and retrieves data from the API.
    /// - The retrieved rate matches the value returned by the mocked API (0.90).
    /// - Verifies exactly one external API call is made to fetch the fresh rate.
    /// This test ensures the exchange service correctly identifies stale cache entries and ensures
    /// that up-to-date data is fetched, maintaining data accuracy and integrity.
    ///
    /// @throws InterruptedException if the thread sleep during the test is interrupted
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

    /// Tests the behavior of the exchange rate service when the external API returns an HTTP 500 error.
    /// This test ensures that the circuit breaker properly identifies and handles server-side errors
    /// from the external API by throwing a `ServiceUnavailableException`.
    /// Test Workflow:
    /// - Stubs the external API endpoint (`/v6/test-dummy-key/latest/USD`) to respond with an HTTP 500 status.
    /// - Invokes the `exchangeService.getRate` method with specific currency parameters (e.g., "USD" to "EUR").
    /// Expectations:
    /// - Verifies that a `ServiceUnavailableException` is thrown when the external service is unavailable.
    /// - The thrown exception contains a message indicating that the external exchange rate API is unavailable.
    /// This test validates the resilience and error-handling capabilities of the exchange service, ensuring
    /// that it fails gracefully under external API failure conditions while maintaining system stability.
    @Test
    @DisplayName("Circuit Breaker: Should throw ServiceUnavailableException on External 500")
    void shouldHandleExternalFailure() {
        stubFor(get(urlPathMatching("/v6/test-dummy-key/latest/USD"))
                .willReturn(aResponse().withStatus(500)));

        assertThatThrownBy(() -> exchangeService.getRate("USD", "EUR"))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("External exchange rate API unavailable");
    }


    /// Validates the behavior of the exchange rate service when requesting the exchange rate
    /// for the same base and target currency.
    /// This test ensures that:
    /// - The service immediately returns a rate of 1.0 without performing any external API calls
    ///   or network operations when the base and target currencies are identical.
    /// Test Workflow:
    /// - Invokes the `exchangeService.getRate` method with the same currency code (e.g., "USD" to "USD").
    /// - Confirms the returned exchange rate is `1.0`.
    /// - Verifies that no network requests are made during the process.
    /// Expectations:
    /// - The returned exchange rate is exactly `1.0`.
    /// - The network layer is not accessed, ensuring efficiency and avoiding unnecessary overhead.
    /// This test validates the optimization logic within the exchange service for same-currency
    /// scenarios, ensuring correct behavior and performance.
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

    /// Validates the behavior of the exchange rate service when the external API responds with a "logical error"
    /// in a successful HTTP response.
    /// This test ensures that the service is capable of identifying business-level validation errors from the external
    /// API response (e.g., when the API returns a "result" field with a value of "error") and reacting appropriately
    /// by throwing a `ServiceUnavailableException`.
    /// Test Workflow:
    /// - Stubs the external API endpoint (`/v6/test-dummy-key/latest/USD`) to respond with an HTTP 200 status but with
    ///   a business-level error payload (e.g., `{"result": "error", "error-type": "invalid-key"}`).
    /// - Invokes the `exchangeService.getRate` method with specific parameters (e.g., "USD" to "EUR").
    /// Expectations:
    /// - Verifies that a `ServiceUnavailableException` is thrown when the API response contains a "result" of "error."
    /// - The thrown exception contains a message that clarifies the external API returned an invalid or unexpected response.
    /// This test validates the service's ability to discern between HTTP-level success and logical errors within the
    /// response payload, ensuring robust error handling and clear failure propagation.
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

    /// Tests the behavior of the exchange rate service when a target currency is missing
    /// in the API response. Verifies that an appropriate exception is thrown.
    /// This method:
    /// 1. Mocks an API response for a request to fetch exchange rates, where one of the
    ///    expected currencies (EUR) is not included in the response.
    /// 2. Asserts that the service throws an [IllegalArgumentException] with a
    ///    specific error message when attempting to retrieve the rate for the missing currency.
    /// Preconditions:
    /// - The API stub for the endpoint `/v6/test-dummy-key/latest/USD` should return a
    ///   mock response containing exchange rates without the target currency (EUR).
    /// Postconditions:
    /// - An [IllegalArgumentException] is thrown by the service with a message
    ///   indicating that the target currency is unsupported.
    /// Test annotations:
    /// - `@Test` indicates that this method is a test case.
    /// - `@DisplayName` provides a descriptive name for the test case.
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

    /// Tests the application's behavior when the circuit breaker is in an open state.
    /// Specifically, this test ensures that a custom exception, `ServiceUnavailableException`,
    /// is thrown with the expected message when the circuit breaker for the currency exchange rate
    /// service is forced into the open state and an attempt is made to invoke the service.
    /// Test Steps:
    /// 1. Force the circuit breaker for the exchange rate service into the open state.
    /// 2. Attempt to invoke the exchange rate retrieval service.
    /// 3. Verify that a `ServiceUnavailableException` is thrown with a message indicating
    ///    that the service is temporarily blocked.
    /// This test assumes proper setup and cleanup are handled by methods annotated with
    /// `@BeforeEach` or equivalent mechanisms.
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

    /// Tests the behavior of the application when a timeout occurs during an external API call.
    /// This test ensures that the application can gracefully handle timeouts from the external
    /// exchange rate API. The test simulates a scenario where the external API response is
    /// delayed beyond the default timeout threshold. It validates that the application throws
    /// a `ServiceUnavailableException` to indicate that the service is currently unavailable.
    /// The test setup stubs the external API response with a fixed delay, forcing a timeout.
    /// During the execution, the service's fallback mechanism is verified to properly handle
    /// the error and provide a meaningful exception.
    /// Assertions:
    /// - Confirms that a `ServiceUnavailableException` is thrown when a timeout occurs.
    /// - Validates that the system fails gracefully during such scenarios.
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


    /// Test method to verify the handling of a raw [TimeoutException] in the exchange rate service.
    /// This test ensures the system can properly detect and handle a sneaky-thrown [TimeoutException]
    /// generated during the execution of the `acquirePermission()` method on the circuit breaker. The test
    /// forces a runtime scenario where a checked [TimeoutException], not normally declared by the
    /// compiled `Supplier` interface, is thrown to validate the fallback behavior of the exchange rate service.
    /// The expected behavior for this test case is:
    /// - The thrown [TimeoutException] is caught by the service.
    /// - A [ServiceUnavailableException] is raised, indicating a timeout condition.
    /// - The exception contains the message "Currency Exchange timed out."
    /// This test is critical for assessing the resilience of the service in scenarios where external timeouts occur,
    /// ensuring graceful degradation and appropriate exception translation for downstream consumers.
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

    /// Tests the exchange rate service's resilience by verifying its ability to handle unexpected runtime errors.
    /// The test simulates an unexpected [RuntimeException] being thrown by the mocked
    /// `exchangeRateCircuitBreaker` during permission acquisition. This bypasses regular
    /// API logic, triggering the fallback mechanism and hitting the catch-all error logging.
    /// The test asserts that:
    /// - A [ServiceUnavailableException] is thrown in response to the unexpected error.
    /// - The thrown exception contains an appropriate error message, indicating the service's unavailability
    ///   and including the details of the simulated error.
    /// This ensures the service's ability to gracefully handle and recover from unexpected runtime exceptions.
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

    /// Test method to verify the behavior of the system when validation failures occur and are propagated
    /// during the execution of a fallback mechanism.
    /// This method simulates a validation error by using a spy to throw an [IllegalArgumentException]
    /// within the execution path of the fallback mechanism. It ensures that the exception is properly propagated
    /// and that the system behaves as expected under such circumstances.
    /// Key operations include:
    /// 1. Forcing an [IllegalArgumentException] with a specific message through a mocked dependency.
    /// 2. Executing the method under test within the fallback mechanism.
    /// 3. Validating via assertions that the exception is thrown and contains the expected message.
    /// Assertions include:
    /// - The exception thrown is an instance of [IllegalArgumentException].
    /// - The exception message contains the expected text.
    @Test
    @DisplayName("Logic: Should Propagate Validation Errors in Fallback")
    void shouldHandleValidationFailureInFallback() {
        // Force an IllegalArgumentException via the Spy to guarantee the 'instanceof' check is covered
        doThrow(new IllegalArgumentException("Forced Validation Error")).when(exchangeRateCircuitBreaker).acquirePermission();

        assertThatThrownBy(() -> exchangeService.getRate("USD", "EUR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forced Validation Error");
    }

    /// A utility method that enables throwing checked exceptions without explicitly declaring them.
    ///
    /// @param e the throwable instance to be thrown, which may include checked exceptions
    /// @param <E> the type of the throwable, inferred to allow unchecked exception propagation
    /// @throws E the throwable cast to the generic type, enabling sneaky propagation
    // --- MAGIC HELPER ---
    // Allows throwing checked exceptions (like TimeoutException) without declaring them.
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable e) throws E {
        throw (E) e;
    }
}