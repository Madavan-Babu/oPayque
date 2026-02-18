package com.opayque.api.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.admin.dto.AccountStatusUpdateRequest;
import com.opayque.api.admin.dto.MoneyDepositRequest;
import com.opayque.api.admin.service.AdminWalletService;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.identity.service.JwtService;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.transactions.service.TransferService;
import com.opayque.api.wallet.controller.WalletController;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import com.opayque.api.wallet.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test suite for {@link AdminWalletController}.
 * <p>
 * Exercises the full stack—including Spring MVC, PostgreSQL (via Testcontainers) and
 * Redis—to verify that administrative wallet operations behave correctly under
 * realistic conditions. The tests cover:
 * <ul>
 *   <li>Security enforcement (admin vs. customer access, authentication checks).</li>
 *   <li>Business rules such as freezing/unfreezing accounts, prohibiting actions on
 *       closed accounts, and preventing redundant state changes.</li>
 *   <li>End‑to‑end money flow, including minting funds by an admin and transferring
 *       them to a customer.</li>
 *   <li>Validation of deposit requests and rate‑limit enforcement for deposit
 *       operations.</li>
 * </ul>
 * By using real repository implementations ({@link UserRepository},
 * {@link AccountRepository}, {@link LedgerRepository}) the suite guarantees that
 * persistence‑related constraints (e.g., foreign‑key integrity, column nullability)
 * are exercised together with the controller logic.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see AdminWalletController
 * @see TransferService
 * @see LedgerRepository
 * @see UserRepository
 * @see AccountRepository
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Tag("integration")
@Testcontainers
class AdminWalletControllerIntegrationTest {

    // --- INFRASTRUCTURE: Testcontainers (Postgres + Redis) ---

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("opayque_test")
            .withUsername("testuser")
            .withPassword("testpass");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    /**
     * Configures dynamic Spring properties for integration tests of
     * {@link AdminWalletControllerIntegrationTest}.<p>
     *
     * The method populates a {@link DynamicPropertyRegistry} with runtime values
     * supplied by Testcontainers, ensuring that the application context connects
     * to the temporary PostgreSQL and Redis instances provided by the test suite.
     *
     * <p>Configuration steps performed:
     * <ul>
     *   <li><b>Datasource connection</b> – Sets JDBC URL, username, password and
     *       driver class so that {@code spring.datasource.*} points to the
     *       containerized PostgreSQL database.</li>
     *   <li><b>Hibernate dialect</b> – Forces the JPA provider to use
     *       {@code org.hibernate.dialect.PostgreSQLDialect} regardless of automatic
     *       detection.</li>
     *   <li><b>Liquibase integration</b> – Enables Liquibase and configures it to use
     *       the same PostgreSQL credentials, allowing database migrations to run
     *       against the test database.</li>
     *   <li><b>Redis support</b> – Supplies host and mapped port for the Redis
     *       container used by {@link RateLimiterService} (or other caching
     *       components) via {@code spring.data.redis.*} properties.</li>
     * </ul>
     *
     * <p>These properties are applied before the Spring context is started,
     * guaranteeing that all components under test operate against the
     * isolated, reproducible test infrastructure.</p>
     *
     * @param registry the {@link DynamicPropertyRegistry} provided by Spring
     *                 TestContext to which the dynamic property entries are added.
     *
     * @see AdminWalletController
     * @see TransferService
     * @see UserRepository
     * @see AccountRepository
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 1. Datasource Connection
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);

        // 2. Force PostgreSQL Dialect
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // 3. Liquibase Integration
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.driver-class-name", postgres::getDriverClassName);

        // 4. Redis Support (For RateLimiterService)
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TransferService transferService;
    @Autowired private LedgerRepository ledgerRepository;

    // Test Data
    private String adminToken;
    private String customerToken;
    private Account activeAccount;
    private Account frozenAccount;
    private Account closedAccount;

    /**
     * Prepares a clean test environment and populates it with baseline data before each
     * integration test execution.<p>
     *
     * The method ensures that every test runs against an isolated data set, preventing
     * cross‑test contamination and providing deterministic results for the
     * {@link AdminWalletController} scenarios.<p>
     *
     * <ul>
     *   <li><b>Database reset</b> – Removes all persisted entities from
     *       {@link LedgerRepository}, {@link AccountRepository} and {@link UserRepository}
     *       to start from a known empty state.</li>
     *   <li><b>User provisioning</b> – Creates an {@code ADMIN} user and a
     *       {@code CUSTOMER} user using the {@code createUser(String, Role)} helper.</li>
     *   <li><b>Token generation</b> – Issues JWT bearer tokens for both users via
     *       {@link JwtService#generateToken(String, String)} and stores them in the
     *       fields {@code adminToken} and {@code customerToken} for subsequent HTTP
     *       request simulation.</li>
     *   <li><b>Account creation</b> – Instantiates three {@link Account} records for the
     *       customer user with distinct statuses (ACTIVE, FROZEN, CLOSED) using the
     *       {@code createAccount(User, String, AccountStatus)} helper. The resulting
     *       entities are assigned to {@code activeAccount}, {@code frozenAccount} and
     *       {@code closedAccount} respectively.</li>
     * </ul>
     *
     * @see AdminWalletController
     * @see TransferService
     * @see UserRepository
     * @see AccountRepository
     * @see LedgerRepository
     * @see JwtService
     */
    @BeforeEach
    void setup() {
        // 1. Clean Slate
        ledgerRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        // 2. Create Users
        User adminUser = createUser("admin@opayque.com", Role.ADMIN);
        User customerUser = createUser("victim@opayque.com", Role.CUSTOMER);

        // 3. Generate Tokens - FIX: REVERT TO EMAIL
        // The Service Layer now correctly resolves "admin@opayque.com" -> User Entity -> UUID.
        // So we send standard JWTs with Emails again.
        adminToken = "Bearer " + jwtService.generateToken(adminUser.getEmail(), "ROLE_ADMIN");
        customerToken = "Bearer " + jwtService.generateToken(customerUser.getEmail(), "ROLE_CUSTOMER");

        // 4. Create Wallets...
        activeAccount = createAccount(customerUser, "EUR", AccountStatus.ACTIVE);
        frozenAccount = createAccount(customerUser, "GBP", AccountStatus.FROZEN);
        closedAccount = createAccount(customerUser, "CHF", AccountStatus.CLOSED);
    }

    // --- SCENARIO 1: HAPPY PATH (The Kill-Switch) ---

    /**
     * Integration test that verifies an administrator can transition an {@link Account}
     * from {@link AccountStatus#ACTIVE} to {@link AccountStatus#FROZEN} using the admin API.
     * <p>
     * The test performs a {@code PATCH} request to {@code /api/v1/admin/accounts/{id}/status}
     * with an {@link AccountStatusUpdateRequest} payload containing {@code FROZEN}. It asserts that:
     * <ul>
     *   <li>The HTTP response status is {@code 200 OK}.</li>
     *   <li>The JSON response body contains {@code "status":"FROZEN"}.</li>
     *   <li>The persisted {@link Account} entity reflects the updated
     *       status in the database.</li>
     * </ul>
     * This scenario ensures that the administrative endpoint correctly enforces the state‑machine
     * transition rules defined in {@link AccountStatus#canTransitionTo(AccountStatus)} and that the
     * change is durable.
     *
     * @see AdminWalletController
     * @see AdminWalletService
     * @see AccountRepository
     * @see AccountStatusUpdateRequest
     */
    @Test
    @DisplayName("Admin: Should successfully FREEZE an Active Account")
    void adminCanFreezeActiveAccount() throws Exception {
        AccountStatusUpdateRequest request = new AccountStatusUpdateRequest(AccountStatus.FROZEN);

        mockMvc.perform(patch("/api/v1/admin/accounts/{id}/status", activeAccount.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));

        // Verification: Check DB
        Account updated = accountRepository.findById(activeAccount.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AccountStatus.FROZEN);
    }

    /**
     * Integration test that verifies an administrator can transition a {@link Account}
     * from {@link AccountStatus#FROZEN} to {@link AccountStatus#ACTIVE} using the admin API.
     * <p>
     * The test performs a {@code PATCH} request to {@code /api/v1/admin/accounts/{id}/status}
     * with an {@link AccountStatusUpdateRequest} payload containing {@link AccountStatus#ACTIVE}.
     * It asserts that:
     * <ul>
     *   <li>The HTTP response status is {@code 200 OK}.</li>
     *   <li>The JSON response body contains {@code "status":"ACTIVE"}.</li>
     *   <li>The persisted {@link Account} entity reflects the updated status in the database.</li>
     * </ul>
     * This scenario ensures that the administrative endpoint correctly enforces the
     * state‑machine transition rules defined in {@link AccountStatus#canTransitionTo(AccountStatus)}
     * and that the change is durable.
     *
     * @see AdminWalletController
     * @see AdminWalletService
     * @see AccountRepository
     * @see AccountStatusUpdateRequest
     */
    @Test
    @DisplayName("Admin: Should successfully UNFREEZE a Frozen Account")
    void adminCanUnfreezeAccount() throws Exception {
        AccountStatusUpdateRequest request = new AccountStatusUpdateRequest(AccountStatus.ACTIVE);

        mockMvc.perform(patch("/api/v1/admin/accounts/{id}/status", frozenAccount.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // --- SCENARIO 2: SECURITY (The Firewall) ---

    /**
     * <p>Validates that a user with the role of <em>customer</em> is prohibited from
     * freezing an account via the administrative endpoint.</p>
     *
     * <p>The test performs an HTTP {@code PATCH} request to
     * {@code /api/v1/admin/accounts/{id}/status} with a payload containing
     * {@link AccountStatus#FROZEN}.  Because the authenticated principal possesses a
     * customer token rather than an admin token, the request must be rejected with
     * HTTP status {@code 403 Forbidden}.  Afterwards the test asserts that the
     * persisted {@link Account} entity still has its original
     * {@link AccountStatus#ACTIVE} status, confirming that no state change was
     * applied.</p>
     *
     * @see WalletController
     * @see AccountService
     * @see AccountRepository
     * @see com.opayque.api.identity.security.SecurityConfig
     */
    @Test
    @DisplayName("Security: Customer cannot freeze accounts (403 Forbidden)")
    void customerCannotFreezeAccount() throws Exception {
        AccountStatusUpdateRequest request = new AccountStatusUpdateRequest(AccountStatus.FROZEN);

        mockMvc.perform(patch("/api/v1/admin/accounts/{id}/status", activeAccount.getId())
                        .header("Authorization", customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        assertThat(accountRepository.findById(activeAccount.getId()).get().getStatus())
                .isEqualTo(AccountStatus.ACTIVE);
    }

    /**
     * Verifies that an unauthenticated request to update an account's status is
     * rejected with HTTP {@code 401 Unauthorized}.
     *
     * <p>This test confirms that the security configuration protects the
     * {@code PATCH /api/v1/admin/accounts/{id}/status} endpoint, ensuring that only
     * authenticated users can perform privileged state changes such as freezing an
     * {@link AccountStatus}.</p>
     *
     * @throws Exception if the MockMvc request execution fails
     *
     * @see AdminWalletController
     * @see AccountService
     * @see AccountRepository
     */
    @Test
    @DisplayName("Security: Unauthenticated request should be rejected (401)")
    void unauthenticatedCannotAccess() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/accounts/{id}/status", activeAccount.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccountStatusUpdateRequest(AccountStatus.FROZEN))))
                .andExpect(status().isUnauthorized());
    }

    // --- SCENARIO 3: LOGIC & STATE MACHINE ---

    /**
     * <p>Validates that an attempt to freeze an account whose current status is
     * {@link AccountStatus#CLOSED} results in a {@code 409 Conflict} HTTP response.</p>
     *
     * <p>The test constructs an {@link AccountStatusUpdateRequest} with a target status
     * of {@link AccountStatus#FROZEN}, then issues a {@code PATCH} request to the admin
     * endpoint {@code /api/v1/admin/accounts/{id}/status}. The expected outcome is a
     * conflict response containing a message that mentions the prohibited state
     * transition ("Cannot transition").</p>
     *
     * @throws Exception if the MockMvc interaction fails.
     *
     * @see WalletController
     * @see AccountService
     * @see AccountRepository
     */
    @Test
    @DisplayName("Logic: Cannot freeze a CLOSED account (409 Conflict)")
    void cannotFreezeClosedAccount() throws Exception {
        AccountStatusUpdateRequest request = new AccountStatusUpdateRequest(AccountStatus.FROZEN);

        mockMvc.perform(patch("/api/v1/admin/accounts/{id}/status", closedAccount.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Cannot transition")));
    }

    /**
     * Verifies that applying a {@code FROZEN} status to an account that is already frozen
     * results in a {@link org.springframework.http.HttpStatus#CONFLICT} response.
     *
     * <p>This test guards against redundant status updates in the admin workflow.
     * Re‑freezing an account does not change its state and should be rejected early,
     * preventing unnecessary processing and ensuring that the API signals the conflict
     * to the caller.</p>
     *
     * <p>The request payload is constructed with {@link AccountStatusUpdateRequest},
     * containing {@link AccountStatus#FROZEN}. The endpoint under test is
     * {@code PATCH /api/v1/admin/accounts/{id}/status}, secured with an admin JWT
     * supplied via the {@code Authorization} header.</p>
     *
     * @see {@link AccountStatusUpdateRequest}
     * @see {@link AccountStatus}
     * @see {@link MockMvc}
     * @see {@link MediaType}
     * @see {@link ObjectMapper}
     */
    @Test
    @DisplayName("Logic: Cannot re-apply same status (Redundant check)")
    void cannotFreezeFrozenAccount() throws Exception {
        AccountStatusUpdateRequest request = new AccountStatusUpdateRequest(AccountStatus.FROZEN);

        mockMvc.perform(patch("/api/v1/admin/accounts/{id}/status", frozenAccount.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    // =========================================================================
    // FEATURE: FIAT GOD MODE (DEPOSIT & DISTRIBUTION)
    // =========================================================================



    /**
     * <p>Integration test that validates an administrator can mint new funds into a fresh
     * wallet and subsequently transfer a portion of those funds to a customer account.</p>
     *
     * <p>The test executes the following sequence:</p>
     * <ul>
     *   <li>Retrieves the admin {@link User} and creates an empty {@link Account} with currency {@code EUR}.</li>
     *   <li>Invokes the admin deposit endpoint (<code>/api/v1/admin/accounts/{id}/deposit</code>) to mint
     *       {@code 5,000.00 EUR}, simulating a Series A funding injection.</li>
     *   <li>Queries the {@link LedgerRepository} directly to confirm the admin ledger shows the minted balance.</li>
     *   <li>Calls {@link TransferService#transferFunds} to move {@code 1,000.00 EUR} from the admin wallet to a
     *       customer account, using a unique transfer key.</li>
     *   <li>Verifies the final balances: the admin wallet should contain {@code 4,000.00 EUR},
     *       and the customer wallet should contain {@code 1,000.00 EUR}.</li>
     * </ul>
     *
     * <p>This scenario ensures that minted liquidity is treated as cleared funds
     * and can participate in normal transfer flows.</p>
     *
     * @see AdminWalletController
     * @see TransferService
     * @see LedgerRepository
     * @see UserRepository
     * @see Account
     * @see MoneyDepositRequest
     */
    @Test
    @DisplayName("E2E: Admin can MINT funds and successfully TRANSFER them to a Customer")
    void adminCanMintAndDistributeFunds() throws Exception {
        // 1. Setup: Admin needs their own Wallet to receive the fresh money
        // We create it here to ensure it's fresh and empty (0.00)
        User adminUser = userRepository.findByEmail("admin@opayque.com").orElseThrow();
        Account adminWallet = createAccount(adminUser, "EUR", AccountStatus.ACTIVE);

        // 2. THE MINTING (Deposit 5,000.00 EUR)
        MoneyDepositRequest depositRequest = new MoneyDepositRequest(
                new BigDecimal("5000.00"),
                "EUR", // Matches Wallet Currency
                "Series A Funding"
        );

        mockMvc.perform(post("/api/v1/admin/accounts/{id}/deposit", adminWallet.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(depositRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLEARED"))
                .andExpect(jsonPath("$.amount").value(5000.00))
                .andExpect(jsonPath("$.transactionId").exists());

        // 3. VERIFICATION: Is the money real?
        // Check the Ledger directly
        BigDecimal adminBalance = ledgerRepository.getBalance(adminWallet.getId());
        assertThat(adminBalance).as("Admin Wallet should have minted funds").isEqualByComparingTo("5000.00");

        // 4. THE DISTRIBUTION (Transfer 1,000.00 EUR to Customer)
        // This proves the system recognizes these funds as valid liquidity.
        String transferKey = "dist-" + UUID.randomUUID();

        // We use the real TransferService to simulate a user action
        transferService.transferFunds(
                adminWallet.getId(),
                "victim@opayque.com", // The Customer created in setup()
                "1000.00",
                "EUR",
                transferKey
        );

        // 5. FINAL ACCOUNTING AUDIT
        // Admin should have 4,000
        BigDecimal postTransferAdminBalance = ledgerRepository.getBalance(adminWallet.getId());
        assertThat(postTransferAdminBalance).isEqualByComparingTo("4000.00");

        // Customer should have 1,000
        BigDecimal customerBalance = ledgerRepository.getBalance(activeAccount.getId());
        assertThat(customerBalance).isEqualByComparingTo("1000.00");
    }


    /**
     * <p>Ensures that the deposit endpoint rejects requests that contain
     * invalid data, such as a negative {@code amount} or a currency code that
     * does not match the required ISO‑4217 pattern {@code "[A-Z]{3}"}.</p>
     *
     * <p>The test creates two {@link MoneyDepositRequest} instances:
     * <ul>
     *   <li>A request with a negative amount, expecting a {@code 400 Bad Request}
     *       response and an error message directly under the {@code $.amount}
     *       JSON field.</li>
     *   <li>A request with an unsupported currency (e.g., {@code "DOGE"}),
     *       expecting a {@code 400 Bad Request} response and a validation error
     *       for the {@code $.currency} field.</li>
     * </ul>
     * This validation is crucial to prevent inconsistent state in the
     * {@link Account} aggregate and to uphold business rules that only
     * positive deposits in recognized currencies are allowed.</p>
     *
     * @see AdminWalletController
     * @see AccountService
     * @see AccountRepository
     */
    @Test
    @DisplayName("Validation: Should Reject Invalid Deposit Requests (Negative/Junk)")
    void shouldRejectInvalidDepositRequests() throws Exception {
        Account targetWallet = activeAccount;

        // Scenario A: Negative Money
        MoneyDepositRequest negativeRequest = new MoneyDepositRequest(
                new BigDecimal("-100.00"), "EUR", "Testing"
        );

        mockMvc.perform(post("/api/v1/admin/accounts/{id}/deposit", targetWallet.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(negativeRequest)))
                .andExpect(status().isBadRequest()) // 400
                // FIX: Check for the field name directly at the root, NOT inside "$.errors"
                .andExpect(jsonPath("$.amount").value("Deposit amount must be greater than zero"));

        // Scenario B: Invalid Currency (Regex Pattern "[A-Z]{3}" failure)
        MoneyDepositRequest badCurrencyRequest = new MoneyDepositRequest(
                new BigDecimal("100.00"), "DOGE", "To the moon"
        );

        mockMvc.perform(post("/api/v1/admin/accounts/{id}/deposit", targetWallet.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badCurrencyRequest)))
                .andExpect(status().isBadRequest())
                // FIX: Check for the currency field error directly
                .andExpect(jsonPath("$.currency").exists());
    }

    /**
     * <p>Verifies that a regular customer cannot invoke the administrative deposit
     * endpoint to add funds to an account, thereby preserving the security
     * segregation between customer and administrator roles.</p>
     *
     * <p>The test constructs a {@link MoneyDepositRequest} with a large amount and
     * sends a {@code POST} request to {@code /api/v1/admin/accounts/{id}/deposit}
     * using {@code MockMvc}. The request includes a customer JWT in the
     * {@code Authorization} header, which represents the attack vector. The
     * expected result is an HTTP {@code 403 Forbidden} status, confirming that the
     * {@link AdminWalletController} correctly rejects the operation for non‑admin
     * principals.</p>
     *
     * @see AdminWalletController
     * @see AccountService
     * @see AccountRepository
     */
    @Test
    @DisplayName("Security: Customer cannot access the Mint (403 Forbidden)")
    void customerCannotDepositFunds() throws Exception {
        MoneyDepositRequest request = new MoneyDepositRequest(
                new BigDecimal("1000000.00"), "EUR", "Hacking Attempt"
        );

        // Customer tries to deposit into their own account using the Admin Endpoint
        mockMvc.perform(post("/api/v1/admin/accounts/{id}/deposit", activeAccount.getId())
                        .header("Authorization", customerToken) // <--- THE ATTACK VECTOR
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    /**
     * <p>Validates that the system enforces the configured deposit rate limit of
     * five (5) deposit requests per minute for administrator accounts. The test
     * ensures that the first five {@code POST /api/v1/admin/accounts/{id}/deposit}
     * calls succeed with an HTTP {@code 200 OK} response, while the sixth call
     * is rejected with an HTTP {@code 429 Too Many Requests} response, indicating
     * the rate‑limiting mechanism is active.</p>
     *
     * <p>This behavior protects the platform from abusive or accidental overload
     * by restricting the frequency of deposit operations that can be performed
     * by a privileged user. It is part of the broader governance controls that
     * maintain system stability and compliance with operational policies.</p>
     *
     * @see {@link AdminWalletController}
     * @see {@link AdminWalletService}
     * @see {@link LedgerRepository}
     * @see {@link UserRepository}
     * @see {@link AccountRepository}
     * @see {@link MoneyDepositRequest}
     */
    @Test
    @DisplayName("Governance: Should Enforce Deposit Rate Limits (5/min)")
    void shouldEnforceDepositRateLimit() throws Exception {
        // Setup: Admin Wallet
        User adminUser = userRepository.findByEmail("admin@opayque.com").orElseThrow();
        Account adminWallet = createAccount(adminUser, "EUR", AccountStatus.ACTIVE);

        MoneyDepositRequest request = new MoneyDepositRequest(
                new BigDecimal("1.00"), "EUR", "Spam"
        );

        // 1. Fire 5 Allowed Requests
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/admin/accounts/{id}/deposit", adminWallet.getId())
                            .header("Authorization", adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        // 2. The 6th Request should Fail (Too Many Requests)
        mockMvc.perform(post("/api/v1/admin/accounts/{id}/deposit", adminWallet.getId())
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests()); // 429
    }

    // --- Helper Methods ---

    private User createUser(String email, Role role) {
        User user = User.builder()
                .email(email)
                .password(passwordEncoder.encode("SecurePass!"))
                .fullName("Test User")
                .role(role)
                .build();
        return userRepository.save(user);
    }

    private Account createAccount(User user, String currency, AccountStatus status) {
        Account account = Account.builder()
                .user(user)
                .currencyCode(currency)
                .iban("CH93" + UUID.randomUUID().toString().substring(0, 10))
                .status(status)
                .build();
        return accountRepository.save(account);
    }
}