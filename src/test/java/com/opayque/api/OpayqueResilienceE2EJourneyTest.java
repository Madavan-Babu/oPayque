package com.opayque.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.opayque.api.identity.controller.AuthController;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.integration.currency.CurrencyExchangeService;
import com.opayque.api.wallet.repository.AccountRepository;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/**
 * <p>E2E resilience journey test that validates the system’s behavior when the
 * external exchange‑rate API experiences an outage.</p>
 *
 * <p>The test spins up real infrastructure components (PostgreSQL, Redis) using
 * Testcontainers and simulates the third‑party API with a {@link WireMockServer}.
 * It exercises the full stack from authentication through account provisioning,
 * funds seeding, and currency conversion, verifying that:</p>
 *
 * <ul>
 *   <li>Users and wallets can be created and funded under normal conditions.</li>
 *   <li>The {@link CurrencyExchangeService} correctly retrieves live rates when
 *       the external service is healthy.</li>
 *   <li>The Actuator health endpoint reflects the outage status when the API
 *       returns HTTP 503.</li>
 *   <li>A circuit‑breaker with caching provides the last known good rate,
 *       ensuring continued operation without throwing exceptions.</li>
 * </ul>
 *
 * <p>This class demonstrates the integration of Spring Boot testing utilities,
 * dynamic property registration, and Resilience4j‑style fault tolerance
 * mechanisms in a realistic end‑to‑end scenario.</p>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see CurrencyExchangeService
 * @see AuthController
 * @see com.opayque.api.wallet.controller.WalletController
 * @see com.opayque.api.admin.controller.AdminWalletController
 * @see UserRepository
 * @see AccountRepository
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("E2E Resilience Journey: Surviving an External API Outage")
public class OpayqueResilienceE2EJourneyTest {

    @LocalServerPort
    private int port;

    // 1. The Real Infrastructure
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.0-alpine"))
            .withExposedPorts(6379);

    // 2. The External World Simulator (WireMock)
    private static WireMockServer wireMockServer;
    private static final String MOCK_API_KEY = "chaos-key";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        postgres.start();
        redis.start();

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);

        // Force PostgreSQL Dialect for Hibernate
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // Liquibase Integration
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.driver-class-name", postgres::getDriverClassName);

        // Hibernate Logic
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // Redis Support
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        // Start WireMock and wire it into the Spring Environment
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        registry.add("application.integration.exchange-rate.base-url", wireMockServer::baseUrl);
        registry.add("application.integration.exchange-rate.api-key", () -> MOCK_API_KEY);
    }

    // State Variables
    private String aliceToken, bobToken, adminToken;
    private String aliceWalletId, bobWalletId;

    @Autowired
    private CurrencyExchangeService currencyExchangeService;

    @BeforeAll
    public void setupRestAssured() {
        RestAssured.port = port;
        RestAssured.baseURI = "http://localhost";
    }

    @AfterAll
    public void teardown() {
        if (wireMockServer != null) wireMockServer.stop();
    }

    /**
     * <p>Initialises the test ecosystem by provisioning two regular users
     * (Alice and Bob) and an administrator, then creates wallets for the
     * users and seeds Alice's wallet with an initial EUR balance.</p>
     *
     * <p>This step is fundamental for subsequent end‑to‑end scenarios because
     * it establishes distinct identities, authentication tokens, and monetary
     * accounts that later tests manipulate.</p>
     *
     * <p>The method performs the following actions in order:</p>
     * <ul>
     *   <li>{@code POST /api/v1/auth/register} – registers Alice.</li>
     *   <li>{@code POST /api/v1/auth/login} – obtains {@code aliceToken}.</li>
     *   <li>Repeats registration and login for Bob, storing {@code bobToken}.</li>
     *   <li>{@code POST /api/v1/auth/login} for the pre‑seeded admin, storing {@code adminToken}.</li>
     *   <li>{@code POST /api/v1/accounts} – creates an EUR wallet for Alice, persisting its identifier as {@code aliceWalletId}.</li>
     *   <li>{@code POST /api/v1/accounts} – creates a GBP wallet for Bob, persisting its identifier as {@code bobWalletId}.</li>
     *   <li>{@code POST /api/v1/admin/accounts/{walletId}/deposit} – deposits {@code 1000.00 EUR} into Alice's wallet using the admin token.</li>
     * </ul>
     *
     * <p>All requests are executed using RestAssured with {@code ContentType.JSON}
     * and verify successful HTTP status codes (201 for creation, 200 for login
     * and deposit).</p>
     *
     * @see OpayqueResilienceE2EJourneyTest
     */
    @Test
    @Order(1)
    @DisplayName("Step 1: The Setup - Provision Users & Wallets")
    public void step1_setupEcosystem() {
        // 1. Register & Login Alice
        given().contentType(ContentType.JSON)
                .body("{ \"email\": \"alice@opayque.com\", \"password\": \"Password123!\", \"fullName\": \"Alice Wonderland\" }")
                .when().post("/api/v1/auth/register").then().statusCode(201);

        aliceToken = given().contentType(ContentType.JSON)
                .body("{ \"email\": \"alice@opayque.com\", \"password\": \"Password123!\" }")
                .when().post("/api/v1/auth/login")
                .then().statusCode(200).extract().path("token");

        // 2. Register & Login Bob
        given().contentType(ContentType.JSON)
                .body("{ \"email\": \"bob@opayque.com\", \"password\": \"Password123!\", \"fullName\": \"Bob Builder\" }")
                .when().post("/api/v1/auth/register").then().statusCode(201);

        bobToken = given().contentType(ContentType.JSON)
                .body("{ \"email\": \"bob@opayque.com\", \"password\": \"Password123!\" }")
                .when().post("/api/v1/auth/login")
                .then().statusCode(200).extract().path("token");

        // 3. Login Admin (Already exists thanks to AdminAccountSeeder.java!)
        adminToken = given().contentType(ContentType.JSON)
                .body("{ \"email\": \"admin@opayque.com\", \"password\": \"Admin@1234\" }")
                .when().post("/api/v1/auth/login")
                .then().statusCode(200).extract().path("token");

        // 4. Create Alice EUR Wallet
        aliceWalletId = given().header("Authorization", "Bearer " + aliceToken)
                .contentType(ContentType.JSON).body("{ \"currencyCode\": \"EUR\" }")
                .when().post("/api/v1/accounts")
                .then().statusCode(201).extract().path("id");

        // 5. Create Bob GBP Wallet
        // (IntelliJ will warn this is unused in this specific test, that is 100% fine)
        bobWalletId = given().header("Authorization", "Bearer " + bobToken)
                .contentType(ContentType.JSON).body("{ \"currencyCode\": \"GBP\" }")
                .when().post("/api/v1/accounts")
                .then().statusCode(201).extract().path("id");

        // 6. Admin Funds Alice with 1000 EUR
        given().header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body("{ \"amount\": \"1000.00\", \"currency\": \"EUR\", \"description\": \"Initial Seed\" }")
                .when().post("/api/v1/admin/accounts/" + aliceWalletId + "/deposit")
                .then()
                .log().all()
                .statusCode(200);
    }

    /**
     * <p>Verifies that the {@link CurrencyExchangeService} can successfully retrieve a live
     * exchange rate from the external Forex provider.</p>
     *
     * <p>The test performs the following actions:</p>
     * <ul>
     *   <li>Configures {@link WireMockServer} to stub a {@code GET} request to the
     *       {@code /&lt;MOCK_API_KEY&gt;/latest/EUR} endpoint, returning a static JSON payload
     *       that contains a conversion rate of {@code 0.85} for {@code GBP}.</li>
     *   <li>Invokes {@code currencyExchangeService.getRate("EUR", "GBP")} to fetch the rate.</li>
     *   <li>Asserts that the returned {@link BigDecimal} matches the expected value
     *       {@code new BigDecimal("0.85")}.</li>
     * </ul>
     *
     * <p>This step is essential for the end‑to‑end resilience journey because it confirms
     * the happy‑path interaction with the third‑party API before any failure simulation
     * (e.g., circuit‑breaker activation) is introduced in subsequent steps.</p>
     *
     * @see OpayqueResilienceE2EJourneyTest
     * @see CurrencyExchangeService
     */
    @Test
    @Order(2)
    @DisplayName("Step 2: Sunny Day - Fetch Live Rates")
    public void step2_fetchLiveRates() {
        wireMockServer.stubFor(get(urlEqualTo("/" + MOCK_API_KEY + "/latest/EUR"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ \"result\": \"success\", \"conversion_rates\": { \"GBP\": 0.85 } }")));

        // FIXED: Using the exact method name from CurrencyExchangeService
        BigDecimal rate = currencyExchangeService.getRate("EUR", "GBP");

        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("0.85"), rate);
    }

    /**
     * <p>Simulates an outage of the external exchange‑rate provider and verifies that the
     * application's health indicator correctly reports the failure.</p>
     *
     * <p>The method carries out two primary actions:</p>
     * <ul>
     *   <li>{@code wireMockServer.stubFor(get(urlEqualTo("/" + MOCK_API_KEY + "/latest/EUR"))
     *         .willReturn(aResponse().withStatus(503)))} – configures the mock server to
     *         return HTTP {@code 503 Service Unavailable} for the external API endpoint,
     *         representing a total service outage.</li>
     *   <li>{@code given().header("Authorization", "Bearer " + adminToken)
     *         .when().get("/actuator/health")
     *         .then()
     *         .statusCode(anyOf(is(200), is(503)))
     *         .body("status", is("DOWN"))} – invokes the Spring Boot Actuator health
     *         endpoint with an administrator token and asserts that the top‑level
     *         {@code status} field equals {@code "DOWN"}.</li>
     * </ul>
     *
     * <p>This step is essential for the resilience journey because it forces the
     * {@link CurrencyExchangeService} to encounter a failure, enabling subsequent
     * tests to confirm that circuit‑breaker and caching mechanisms respond appropriately
     * to external service outages.</p>
     *
     * @see OpayqueResilienceE2EJourneyTest
     * @see CurrencyExchangeService
     * @see WireMockServer
     */
    @Test
    @Order(3)
    @DisplayName("Step 3: The Outage - Sabotaging the External API")
    public void step3_sabotageExternalApi() {
        // We pull the plug. The ExchangeRate-API now returns a 503 Service Unavailable.
        wireMockServer.stubFor(get(urlEqualTo("/" + MOCK_API_KEY + "/latest/EUR"))
                .willReturn(aResponse().withStatus(503)));

        // Verify the Actuator Health Sensor correctly detects the outage
        // FIXED: Checking the top-level 'status' field since Spring Boot hides 'components' by default
        given().header("Authorization", "Bearer " + adminToken)
                .when().get("/actuator/health")
                .then()
                .log().all() // Prints the actual JSON so you can see the top-level DOWN
                .statusCode(anyOf(is(200), is(503)))
                .body("status", is("DOWN"));
    }

    /**
     * <p>Validates that the {@link CurrencyExchangeService} correctly serves a cached
     * exchange rate when the external provider is unavailable and the circuit breaker
     * is open.</p>
     *
     * <p>In the preceding test step (<i>Step 3</i>), the {@link WireMockServer}
     * simulation of the third‑party Forex API is deliberately shut down, causing the
     * circuit breaker to transition to the <code>OPEN</code> state. This step ensures
     * that a subsequent invocation of {@code currencyExchangeService.getRate("EUR",
     * "GBP")} does not attempt a remote call but instead returns the previously
     * cached value (<code>0.85</code>) without throwing any exception.</p>
     *
     * <p>The test performs the following actions:</p>
     * <ul>
     *   <li>{@code BigDecimal rate = currencyExchangeService.getRate("EUR", "GBP");}</li>
     *   <li>{@code Assertions.assertEquals(new BigDecimal("0.85"), rate);}</li>
     * </ul>
     *
     * <p>Successful execution confirms that the caching layer (e.g., Redis or in‑memory
     * cache) and the resilience mechanisms (circuit breaker fallback) cooperate as
     * intended, thereby guaranteeing service continuity during external outages.</p>
     *
     * @see OpayqueResilienceE2EJourneyTest
     * @see CurrencyExchangeService
     */
    @Test
    @Order(4)
    @DisplayName("Step 4: The Rescue - Cache Saves the Day")
    public void step4_fetchCachedRates() {
        // At this point, WireMock is DEAD (Step 3).
        // The Circuit Breaker should intercept this call and return the cached 0.85 rate
        // without throwing an exception!

        // FIXED: Using the exact method name from CurrencyExchangeService
        BigDecimal rate = currencyExchangeService.getRate("EUR", "GBP");

        org.junit.jupiter.api.Assertions.assertEquals(new BigDecimal("0.85"), rate);
    }
}