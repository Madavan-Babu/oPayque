package com.opayque.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.opayque.api.card.controller.CardController;
import com.opayque.api.card.dto.CardTransactionRequest;
import com.opayque.api.card.service.CardTransactionService;
import com.opayque.api.identity.controller.AuthController;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.identity.service.AuthService;
import com.opayque.api.statement.controller.StatementController;
import com.opayque.api.statement.service.StatementService;
import com.opayque.api.transactions.controller.TransferController;
import com.opayque.api.transactions.service.TransferService;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.service.AccountService;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * <p>
 * End‑to‑end integration test suite that simulates a realistic user journey
 * through the oPayque platform. The test orchestrates a full lifecycle:
 * registration, authentication, wallet provisioning, admin‑driven funding,
 * virtual card issuance, fund transfer, statement verification and a simulated
 * merchant payment. It validates that all components – REST controllers,
 * services, repositories, external adapters and infrastructure (WireMock,
 * Testcontainers for Postgres and Redis) – collaborate correctly in a production‑like
 * environment.
 * </p>
 *
 * <p>
 * The suite is executed in a defined order using {@code @Order} to mimic a
 * sequential user flow. Shared state such as JWT tokens, wallet identifiers,
 * PAN details and the idempotency key is stored in instance fields, allowing
 * subsequent steps to reference outcomes from earlier stages.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see OPayqueApiApplication
 * @see ApplicationStartupTest
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class OpayqueE2EJourneyTest {

    @LocalServerPort
    private int port;

    // State Variables to hold data between chronological steps
    private String aliceToken;
    private String bobToken;
    private String adminToken;
    private UUID aliceWalletId;
    private UUID bobWalletId;
    private String alicePan;
    private String aliceCvv;
    private String aliceExpiry;
    private String idempotencyKey;

    // Mocks the external ExchangeRate-API
    // REPLACE the @RegisterExtension block with this:
    static WireMockServer wireMockServer = new WireMockServer(wireMockConfig().dynamicPort());

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("opayque_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);


    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 1. FORCE START EVERYTHING FIRST
        postgres.start();
        redis.start();
        wireMockServer.start(); // Starts instantly, no JUnit lifecycle issues!

        // 2. Register the exact active WireMock URL
        registry.add("application.integration.exchange-rate.base-url", wireMockServer::baseUrl);

        // 3. Connection Strings
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);

        // 4. Force PostgreSQL Dialect for Hibernate
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // 5. Liquibase Integration
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.driver-class-name", postgres::getDriverClassName);

        // 6. Hibernate Logic
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // 7. Redis Support
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    /**
     * Sets up the testing infrastructure required for the end‑to‑end journey.
     * <p>
     * Configures {@code RestAssured} to use the dynamically allocated {@code port}
     * and the base URI {@code http://localhost}, ensuring that all HTTP calls made
     * by the tests target the embedded Spring Boot instance.
     * </p>
     * <p>
     * Generates a fresh {@code idempotencyKey} via {@code UUID.randomUUID()}.
     * This key is used throughout the suite to guarantee idempotent request handling
     * and to emulate real‑world client behavior.
     * </p>
     * <p>
     * Stubs the external Forex API with {@code WireMock}.
     */
    @BeforeAll
    public void setup() {
        RestAssured.port = port;
        RestAssured.baseURI = "http://localhost";
        idempotencyKey = UUID.randomUUID().toString();

        // Stub the Forex API to always return a successful response
        wireMockServer.stubFor(WireMock.get(WireMock.urlPathMatching("/.*/latest/EUR"))
                .willReturn(WireMock.aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{ \"result\": \"success\", \"conversionRates\": { \"EUR\": 1.0, \"GBP\": 0.85 } }")
                        .withStatus(200)));
    }

    @AfterAll
    static void tearDown() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            wireMockServer.stop();
        }
    }

    /**
     * Registers the initial user accounts required for the end‑to‑end journey.
     * <p>
     * Executes two {@link RestAssured} {@code POST} requests to
     * {@code /api/v1/auth/register} with JSON payloads for Alice and Bob.
     * Each request is asserted to return HTTP {@code 201} (Created) and to
     * provide a masked email address (e.g., {@code a***@opayque.com}) as
     * defined by the {@code PiiLoggingConverter} implementation.
     * </p>
     * <p>
     * By creating these identities early, subsequent test steps can perform
     * authentication, wallet provisioning, fund transfers, and other operations
     * without external dependencies. The method therefore establishes a known
     * baseline state for the system under test.
     * </p>
     *
     * @see AuthController
     * @see AuthService
     * @see UserRepository
     */
    @Test
    @Order(1)
    @DisplayName("Step 1: Onboarding - Register Alice and Bob")
    public void step1_registerUsers() {
        // Register Alice
        given().contentType(ContentType.JSON)
                .body("{ \"email\": \"alice@opayque.com\", \"password\": \"Secure@123\", \"fullName\": \"Alice Smith\" }")
                .when().post("/api/v1/auth/register")
                .then().statusCode(201)
                // Assert the MASKED version as per your PiiLoggingConverter logic
                .body("email", equalTo("a***@opayque.com"));

        // Register Bob
        given().contentType(ContentType.JSON)
                .body("{ \"email\": \"bob@opayque.com\", \"password\": \"Secure@123\", \"fullName\": \"Bob Jones\" }")
                .when().post("/api/v1/auth/register")
                .then().statusCode(201)
                // Assert the MASKED version
                .body("email", equalTo("b***@opayque.com"));
    }

    /**
     * Step 2 – Identity: Logs in predefined users and extracts JWT tokens.
     * <p>
     * This method authenticates the three baseline identities (Alice, Bob, and Admin) by
     * issuing {@code POST} requests to {@code /api/v1/auth/login}. The response body
     * must contain a token field whose exact name can differ per implementation
     * (e.g., {@code "accessToken"}, {@code "access_token"} or {@code "token"}). The
     * method captures the value using {@code jsonKey} and stores it in the instance
     * fields {@code aliceToken}, {@code bobToken} and {@code adminToken} for reuse in
     * later test steps such as wallet provisioning, fund transfers, and audit
     * verification.
     * </p>
     * <p>
     * Performing authentication at the start of the end‑to‑end journey establishes a
     * known authorized context, ensuring that each subsequent secured request mirrors
     * real‑world client behavior where a bearer token is required.
     * </p>
     *
     * @see AuthController
     * @see AuthService
     * @see UserRepository
     */
    @Test
    @Order(2)
    @DisplayName("Step 2: Identity - Login to obtain JWTs")
    public void step2_loginAndExtractTokens() {
        // IMPORTANT: Verify if your DTO uses "accessToken", "access_token", or "token"
        // Replace "accessToken" below with the exact JSON key returned by your API.
        String jsonKey = "token";

        aliceToken = given().contentType(ContentType.JSON)
                .body("{ \"email\": \"alice@opayque.com\", \"password\": \"Secure@123\" }")
                .when().post("/api/v1/auth/login")
                .then().statusCode(200)
                .body(jsonKey, notNullValue()) // <--- Prevents silent null extraction
                .extract().path(jsonKey);

        bobToken = given().contentType(ContentType.JSON)
                .body("{ \"email\": \"bob@opayque.com\", \"password\": \"Secure@123\" }")
                .when().post("/api/v1/auth/login")
                .then().statusCode(200)
                .body(jsonKey, notNullValue())
                .extract().path(jsonKey);

        adminToken = given().contentType(ContentType.JSON)
                .body("{ \"email\": \"admin@opayque.com\", \"password\": \"Admin@1234\" }")
                .when().post("/api/v1/auth/login")
                .then().statusCode(200)
                .body(jsonKey, notNullValue())
                .extract().path(jsonKey);
    }

    /**
     * Step 3 – Provisioning: Creates EUR wallets for the test identities.
     * <p>
     * This method provisions a separate {@code EUR} account (wallet) for each
     * pre‑registered user (Alice and Bob) by invoking the {@code POST}
     * {@code /api/v1/accounts} endpoint. The response contains the newly created
     * account identifier, which is extracted and stored in the instance fields
     * {@code aliceWalletId} and {@code bobWalletId}. These IDs are later used by
     * subsequent steps to perform deposits, transfers and audit verification.
     * </p>
     * <p>
     * By explicitly creating wallets in the test suite, the end‑to‑end journey
     * mirrors a real‑world onboarding flow where a customer must first obtain a
     * digital account before any monetary operations can be performed.
     * </p>
     *
     * @see com.opayque.api.wallet.controller.WalletController // REST controller handling {@code /api/v1/accounts}
     * @see AccountService         // Service layer processing wallet creation
     * @see AccountRepository      // JPA repository persisting {@code Account} entities
     */
    @Test
    @Order(3)
    @DisplayName("Step 3: Provisioning - Create EUR Wallets")
    public void step3_createWallets() {
        aliceWalletId = UUID.fromString(
                given().header("Authorization", "Bearer " + aliceToken).contentType(ContentType.JSON)
                        .body("{ \"currencyCode\": \"EUR\" }")
                        .when().post("/api/v1/accounts") // Corrected endpoint
                        .then().statusCode(201) // 201 Created
                        .body("iban", notNullValue())
                        // Note: Ensure your AccountResponse DTO uses "accountId" or change this to "id" if needed
                        .extract().path("id")
        );

        bobWalletId = UUID.fromString(
                given().header("Authorization", "Bearer " + bobToken).contentType(ContentType.JSON)
                        .body("{ \"currencyCode\": \"EUR\" }")
                        .when().post("/api/v1/accounts") // Corrected endpoint
                        .then().statusCode(201) // 201 Created
                        .extract().path("id")
        );
    }

    /**
     * Step 4 – Mint: Admin injects a seed amount of {@code 1000 EUR} into Alice’s wallet.
     *
     * <p>This test verifies that an administrator can credit a user account directly,
     * emulating the “mint” operation required for initial fund provisioning in the
     * system. The request is performed against the {@code /api/v1/admin/accounts/{walletId}/deposit}
     * endpoint, using the {@code adminToken} for bearer authentication and the
     * {@code aliceWalletId} to identify the target account.</p>
     *
     * <p>The request payload contains three fields:</p>
     * <ul>
     *   <li>{@code amount} – the monetary value to credit (e.g., {@code "1000.00"})</li>
     *   <li>{@code currency} – ISO‑4217 currency code (e.g., {@code "EUR"})</li>
     *   <li>{@code description} – a free‑form note for audit trails (e.g., {@code "Initial Seed"})</li>
     * </ul>
     *
     * <p>Upon successful processing the API returns HTTP {@code 200} (or {@code 201}
     * depending on the controller implementation) and a JSON body where the
     * {@code amount} field reflects the deposited value as a floating‑point number.
     * This step establishes a known balance for subsequent operations such as
     * virtual‑card issuance and fund transfers.</p>
     *
     * @see com.opayque.api.wallet.controller.WalletController
     * @see AccountService
     * @see AccountRepository
     */
    @Test
    @Order(4)
    @DisplayName("Step 4: The Mint - Admin injects 1000 EUR to Alice")
    public void step4_adminDeposit() {
        given().header("Authorization", "Bearer " + adminToken).contentType(ContentType.JSON)
                .body("{ \"amount\": \"1000.00\", \"currency\": \"EUR\", \"description\": \"Initial Seed\" }")
                .when().post("/api/v1/admin/accounts/" + aliceWalletId + "/deposit") // Corrected endpoint
                .then().statusCode(200) // Keep as 200, or change to 201 if your controller returns Created
                .body("amount", equalTo(1000.0f));
    }

    /**
     * Issues a virtual payment card for the test user **Alice**.
     * <p>
     * This step simulates the card‑issuance workflow of the system. It sends a
     * {@code POST} request to {@code /api/v1/cards/issue} with a JSON payload that
     * specifies the desired {@code currency} (e.g. {@code "EUR"}). The request
     * includes the bearer token obtained during the login step ({@code aliceToken})
     * and a freshly generated {@code Idempotency-Key} header to guarantee that
     * duplicate submissions are ignored by the service.
     * </p>
     * <p>
     * Upon a successful issuance the endpoint returns HTTP {@code 201} (Created)
     * together with a JSON body that contains the card details:
     * <ul>
     *   <li>{@code pan} – the primary account number, which must start with the
     *       virtual‑card prefix {@code 171103}</li>
     *   <li>{@code cvv} – a non‑null security code</li>
     *   <li>{@code expiryDate} – the card’s expiration date</li>
     * </ul>
     * The values are extracted and stored in the test instance fields
     * {@code alicePan}, {@code aliceCvv} and {@code aliceExpiry} for use in later
     * simulation steps (e.g., the merchant‑swipe scenario).
     * </p>
     *
     * @see CardController
     * @see CardTransactionService
     * @see com.opayque.api.card.repository.VirtualCardRepository
     */
    @Test
    @Order(5)
    @DisplayName("Step 5: Card Factory - Issue Virtual Card for Alice")
    public void step5_issueVirtualCard() {
        var jsonPath = given()
                .header("Authorization", "Bearer " + aliceToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(ContentType.JSON)
                // REFACTORED: Sending the required 'currency' instead of 'accountId'
                .body("{ \"currency\": \"EUR\" }")
                .when().post("/api/v1/cards/issue")
                .then()
                .log().all() // Keep this for now to see the success payload!
                .statusCode(201)
                .body("pan", startsWith("171103"))
                .body("cvv", notNullValue())
                .extract().jsonPath();

        // Save the secrets for the merchant simulation in Step 9
        alicePan = jsonPath.getString("pan");
        aliceCvv = jsonPath.getString("cvv");
        aliceExpiry = jsonPath.getString("expiryDate");
    }

    /**
     * <p>Executes step 6 of the integration scenario: transfers 500 EUR from Alice’s wallet to Bob’s account.</p>
     *
     * <p>The test issues a {@code POST} request to {@code /api/v1/transfers} with a JSON body
     * containing {@code senderId}, {@code receiverEmail}, {@code amount}, and {@code currency}.
     * An {@code Idempotency-Key} header is provided to ensure the operation is idempotent,
     * aligning with the contract defined in {@link TransferService}.</p>
     *
     * <p>The expected outcome is an HTTP 200 (or 201 if the controller follows the
     * {@code Created} convention) response that includes a non‑null {@code transferId}
     * and a {@code status} equal to {@code COMPLETED}. The response is fully logged to aid
     * debugging of potential DTO mismatches (e.g., {@code senderAccountId}).</p>
     *
     * @see TransferController
     * @see TransferService
     * @see com.opayque.api.wallet.repository.LedgerRepository
     */
    @Test
    @Order(6)
    @DisplayName("Step 6: Core Engine - Transfer 500 EUR to Bob")
    public void step6_transferFunds() {
        given()
                .header("Authorization", "Bearer " + aliceToken)
                // ADDED: Moved the Idempotency-Key to the HTTP Headers
                .header("Idempotency-Key", idempotencyKey)
                .contentType(ContentType.JSON)
                // REMOVED: IdempotencyKey from the JSON body
                .body("{ \"senderId\": \"" + aliceWalletId + "\", \"receiverEmail\": \"bob@opayque.com\", \"amount\": \"500.00\", \"currency\": \"EUR\" }")
                .when().post("/api/v1/transfers")
                .then()
                .log().all() // Helps debug if the DTO field names are slightly different (e.g. senderAccountId)
                .statusCode(200) // NOTE: Change to 201 if your controller returns Created!
                .body("transferId", notNullValue())
                .body("status", equalTo("COMPLETED"));
    }

    /**
     * <p>Verifies that the audit endpoint for exporting account statements functions correctly.
     * This test simulates a client request to {@code /api/v1/statements/export} using a
     * {@code GET} operation with query parameters representing the account identifier and
     * the date range for which statements are required. The response is expected to have a
     * {@code 200 OK} status and contain specific transaction entries, such as
     * {@code ADMIN_DEPOSIT} and the transfer description to {@code bob@opayque.com}.</p>
     *
     * <p>The purpose of this verification step is to ensure that the settlement export
     * mechanism accurately reflects all activities for the specified day, thereby providing
     * reliable audit data for downstream reconciliation processes.</p>
     *
     * @see StatementController
     * @see StatementService
     */
    @Test
    @Order(7)
    @DisplayName("Step 7: The Audit - Export Statement to verify settlement")
    public void step7_verifyStatement() {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        // Converted from POST JSON body to GET Query Parameters
        given().header("Authorization", "Bearer " + aliceToken)
                .queryParam("accountId", aliceWalletId.toString())
                .queryParam("startDate", today)
                .queryParam("endDate", today)
                .when().get("/api/v1/statements/export") // Changed to GET
                .then().statusCode(200)
                .body(containsString("ADMIN_DEPOSIT"))
                .body(containsString("Transfer to bob@opayque.com"));
    }

    /**
     * Verifies that the receiver (Bob) can retrieve an audit‑ready statement confirming the funds
     * received from the sender (Alice) on the current day.
     * <p>
     * This step validates the end‑to‑end transaction visibility for the beneficiary by invoking the
     * {@code /api/v1/statements/export} endpoint with Bob's authentication token and wallet identifier.
     * The response must contain a successful HTTP status (200) and include the expected sender email
     * and transferred amount, thereby ensuring that the financial operation is correctly recorded
     * and auditable on both sides of the transaction.
     * </p>
     *
     * @see StatementController
     * @see StatementService
     */
    @Test
    @Order(8)
    @DisplayName("Step 8: The Receiver's Audit - Bob verifies funds received")
    public void step8_verifyBobReceivedFunds() {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_DATE);

        given().header("Authorization", "Bearer " + bobToken)
                .queryParam("accountId", bobWalletId.toString())
                .queryParam("startDate", today)
                .queryParam("endDate", today)
                .when().get("/api/v1/statements/export")
                .then().statusCode(200)
                // Reverted to the actual email, as expected on an official statement
                .body(containsString("alice@opayque.com"))
                .body(containsString("500.0000"));
    }

    /**
     * Simulates a merchant swipe of Alice's virtual card as part of the external payment flow.
     * <p>
     * The test generates a unique {@code externalTxId} to mimic the trace identifier produced
     * by an external acquirer (e.g., Stripe) and builds a JSON payload that conforms to the
     * {@link CardTransactionRequest} DTO validation constraints. The payload includes the
     * PAN, CVV, expiry date, amount, currency, merchant name, merchant category code and the
     * generated external transaction ID.
     * <p>
     * The request is sent to {@code /api/v1/simulation/card-transaction} via {@code POST}
     * with an {@code Authorization} header containing an admin JWT. This header acts as a
     * “Trusted Gateway” and satisfies the {@link SecurityFilterChain} defined in the
     * security configuration.
     * <p>
     * A successful response is expected with HTTP status {@code 200} and a response body
     * where the {@code status} field equals {@code "APPROVED"}, confirming that the
     * simulated transaction is processed by the {@link CardController},
     * delegated to the {@link CardTransactionService} for business logic, and persisted
     * through the {@link com.opayque.api.card.repository.VirtualCardRepository}.
     *
     * @see com.opayque.api.wallet.controller.WalletController
     * @see CardTransactionService
     * @see com.opayque.api.card.repository.VirtualCardRepository
     */
    @Test
    @Order(9)
    @DisplayName("Step 9: External Payment - Merchant swipes Alice's Virtual Card")
    public void step9_simulateCardSwipe() {
        // Simulating the unique trace ID generated by an external acquirer (e.g., Stripe)
        String externalTxId = UUID.randomUUID().toString();

        // Building the payload strictly conforming to CardTransactionRequest DTO validations
        String payload = String.format("""
            {
                "pan": "%s",
                "cvv": "%s",
                "expiryDate": "%s",
                "amount": "45.50",
                "currency": "EUR",
                "merchantName": "Amazon Web Services",
                "merchantCategoryCode": "5411",
                "externalTransactionId": "%s"
            }
            """, alicePan, aliceCvv, aliceExpiry, externalTxId);

        // REFACTORED: Attach the Admin JWT to satisfy the strict SecurityFilterChain.
        // This acts as our "Trusted Gateway" authorization.
        given().header("Authorization", "Bearer " + adminToken)
                .contentType(ContentType.JSON)
                .body(payload)
                .when().post("/api/v1/simulation/card-transaction")
                .then().statusCode(200)
                // Assuming your CardTransactionResponse returns an approval status
                .body("status", equalTo("APPROVED"));
    }
}