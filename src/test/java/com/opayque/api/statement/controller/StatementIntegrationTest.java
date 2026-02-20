package com.opayque.api.statement.controller;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.statement.service.StatementService;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import com.opayque.api.wallet.service.LedgerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



/**
 * Integration test suite for the {@link StatementController} statement‑export API.
 * <p>
 * This class validates the end‑to‑end behaviour of the statement export feature
 * across a full stack that includes PostgreSQL 17, Redis 7, Spring MVC,
 * Spring Security and the custom rate‑limiting infrastructure.  By exercising the
 * request pipeline with a real {@link MockMvc} instance and a live database,
 * the tests ensure that:
 * <ul>
 *   <li>the application context boots correctly with the required external services,</li>
 *   <li>security constraints (authentication, role‑based authorization and
 *       Bola‑specific policy checks) are enforced,</li>
 *   <li>input validation (mandatory parameters, date ranges, future dates,
 *       maximum six‑month span) returns appropriate HTTP error codes,</li>
 *   <li>rate‑limit thresholds are respected and trigger {@code 429 Too Many Requests},
 *       </li>
 *   <li>large ledger data streams are processed without excessive memory consumption
 *       (streamed CSV chunks),</li>
 *   <li>CSV injection vectors stored in the database are safely escaped,</li>
 *   <li>unsupported or rogue currency codes are omitted from the output,</li>
 *   <li>PII fields (e.g., email, full name) are masked in CSV headers,</li>
 *   <li>numeric precision is correctly formatted according to currency rules, and</li>
 *   <li>client disconnects are gracefully handled via the {@code IOException}
 *       catch block.</li>
 * </ul>
 * <p>
 * The test harness wires the following repository beans to verify data‑driven
 * scenarios against the real persistence layer:
 * <ul>
 *   <li>{@link UserRepository} – provides access to persisted {@code User} entities
 *       used for authentication token generation.</li>
 *   <li>{@link AccountRepository} – supplies account fixtures for which statements
 *       are generated.</li>
 *   <li>{@link LedgerRepository} – enables creation of massive ledger rows to
 *       stress‑test streaming behaviour.</li>
 * </ul>
 * <p>
 * Redis is employed as a distributed rate‑limit store, and the {@code
 * DynamicPropertySource} method configures the embedded containers at runtime.
 * <p>
 * @see StatementController
 * @see UserRepository
 * @see AccountRepository
 * @see LedgerRepository
 *
 * @author Madavan Babu
 * @since 2026
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@Tag("integration")
@ActiveProfiles("test")
class StatementIntegrationTest {

    // ==================================================================================
    // TRUE E2E INFRASTRUCTURE SETUP (POSTGRES 17 & REDIS 7)
    // ==================================================================================

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("opayque_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 1. Connection Strings
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);

        // 2. Force PostgreSQL Dialect for Hibernate
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // 3. Liquibase Integration
        // Ensure Liquibase is ENABLED and using the correct driver        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.driver-class-name", postgres::getDriverClassName);

        // 4. Hibernate Logic
        // Since Liquibase is creating the tables, Hibernate should only VALIDATE them.        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // 5. Redis Support
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StatementController statementController;

    private User owner;
    private User attacker;
    private User admin;
    private Account targetAccount;


    /**
     * Initializes integration‑test fixtures before each test case.
     *
     * <p>This method creates a set of persisted domain objects that represent
     * common security and business scenarios required by the statement export
     * tests:</p>
     *
     * <ul>
     *   <li><b>Owner</b> – a {@link User} with {@link Role#CUSTOMER} who owns the
     *       {@link Account} under test. The owner is the legitimate subject for
     *       normal export operations.</li>
     *   <li><b>Attacker</b> – another {@link User} with {@link Role#CUSTOMER}
     *       intended to simulate a broken‑object‑level‑authorization (BOLA)
     *       attack. This user does not own the target account.</li>
     *   <li><b>Admin</b> – a {@link User} with {@link Role#ADMIN} used to verify
     *       privileged overrides and RBAC checks.</li>
     *   <li><b>Target Account</b> – an {@link Account} linked to {@code owner}
     *       with a valid IBAN, EUR currency and {@link AccountStatus#ACTIVE}
     *       status. This account serves as the source of statement data.</li>
     * </ul>
     *
     * <p>All entities are saved via the corresponding Spring Data repositories so
     * that they receive generated identifiers and are fully managed by the JPA
     * persistence context. Persisted instances are stored in the class fields
     * {@code owner}, {@code attacker}, {@code admin} and {@code targetAccount}
     * for later use in test methods.</p>
     *
     * @see StatementController
     * @see StatementService
     * @see UserRepository
     * @see AccountRepository
     */
    @BeforeEach
    void setUp() {
        // 1. Setup Owner (Role.CUSTOMER)
        owner = User.builder()
                .fullName("Statement Owner") // Added mandatory field
                .email("owner@opayque.com")
                .password("hashed_password")
                .role(Role.CUSTOMER)
                .build();
        owner = userRepository.save(owner);

        // 2. Setup Attacker (BOLA tester - Role.CUSTOMER)
        attacker = User.builder()
                .fullName("Malicious Actor") // Added mandatory field
                .email("attacker@opayque.com")
                .password("hashed_password")
                .role(Role.CUSTOMER)
                .build();
        attacker = userRepository.save(attacker);

        // 3. Setup Admin (RBAC tester - Role.ADMIN)
        admin = User.builder()
                .fullName("System Administrator") // Added mandatory field
                .email("admin@opayque.com")
                .password("hashed_password")
                .role(Role.ADMIN)
                .build();
        admin = userRepository.save(admin);

        // 4. Setup Target Account for Owner
        targetAccount = Account.builder()
                .user(owner)
                .iban("DE30123456789012345678")
                .currencyCode("EUR")
                .status(AccountStatus.ACTIVE)
                .build();
        targetAccount = accountRepository.save(targetAccount);
    }

    /**
     * Cleans up the integration‑test environment after each test method.
     *
     * <p>The cleanup follows the domain‑model dependency order to avoid foreign‑key violations:
     *
     * <ul>
     *   <li>Deletes all {@link LedgerRepository} entries first because ledger rows reference {@link AccountRepository} accounts.</li>
     *   <li>Deletes all {@link AccountRepository} records next, as accounts reference {@link UserRepository} users.</li>
     *   <li>Deletes all {@link UserRepository} records last, the root entities.</li>
     * </ul>
     *
     * <p>After the database tables are cleared, the method also:
     *
     * <ul>
     *   <li>Resets the Spring Security context with {@link SecurityContextHolder#clearContext()} to prevent thread‑local leakage between tests.</li>
     *   <li>Flushes all Redis data via {@link RedisTemplate} to ensure rate‑limit counters and other cached state do not pollute subsequent test executions.</li>
     * </ul>
     *
     * @see StatementController
     * @see StatementService
     * @see UserRepository
     * @see AccountRepository
     * @see LedgerRepository
     */
    @AfterEach
    void tearDown() {
        // 1. Delete children first (Ledger entries depend on Accounts)
        ledgerRepository.deleteAll();

        // 2. Delete parents (Accounts depend on Users)
        accountRepository.deleteAll();

        // 3. Delete grandparents (Users are the root)
        userRepository.deleteAll();

        // 4. Clear the security context to prevent thread leakage
        SecurityContextHolder.clearContext();

        // 5. Ensure Rate Limits are reset so tests don't pollute each other
        redisTemplate.execute((RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });
    }

    // ==================================================================================
    // PRIVATE TEST HELPERS
    // ==================================================================================

    /**
     * Injects the real persisted User entity into the test SecurityContext.
     * Updated to use Role.CUSTOMER/ADMIN prefixes.
     */
    private UsernamePasswordAuthenticationToken createRealAuthToken(User user) {
        // Prefixes with ROLE_ as per Spring Security standard used in our SecurityConfig
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + user.getRole().name());
        return new UsernamePasswordAuthenticationToken(user, null, Collections.singletonList(authority));
    }

    private String getBaseUrl(UUID accountId) {
        return "/api/v1/statements/export?accountId=" + accountId
                + "&startDate=" + LocalDate.now().minusMonths(1)
                + "&endDate=" + LocalDate.now();
    }


    // ==================================================================================
    // PHASE 1: CONTEXT & INFRASTRUCTURE SANITY
    // ==================================================================================

    /**
     * Integration test that verifies the application context starts correctly with
     * PostgreSQL 17 and Redis 7 containers.
     *
     * <p>The test performs three key assertions:
     * <ul>
     *   <li>Checks that the {@code postgres} container is running; if it is not,
     *       the test fails with a message indicating that the PostgreSQL 17 Alpine
     *       container could not be started.</li>
     *   <li>Checks that the {@code redis} container is running; a failure here signals
     *       that the Redis 7 Alpine container failed to start.</li>
     *   <li>Ensures Liquibase has applied all migrations by asserting that the
     *       {@link AccountRepository} contains at least one record. This confirms that
     *       the JPA persistence context is initialized and ready for further tests.</li>
     * </ul>
     *
     * <p>Successful execution guarantees that the integration‑test environment is
     * properly provisioned for the subsequent statement‑export scenario tests.
     *
     * @see StatementIntegrationTest
     * @see AccountRepository
     * @see StatementController
     * @see StatementService
     */
    // Test 1
    @Test
    void test1_ContextLoads_WithPostgres17AndRedis7() {
        assertTrue(postgres.isRunning(), "PostgreSQL 17 Alpine container failed to start!");
        assertTrue(redis.isRunning(), "Redis 7 Alpine container failed to start!");

        // If we reach this assertion, Liquibase successfully ran all migrations against Postgres 17
        assertTrue(accountRepository.count() > 0, "Database context failed to initialize or save test entities.");
    }


    // ==================================================================================
    // PHASE 2: SECURITY & AUTHORIZATION (BOLA/RBAC E2E)
    // ==================================================================================

    /**
     * <p>Verifies that the statement export endpoint rejects requests that lack an
     * authentication token by returning HTTP <b>401 Unauthorized</b>.</p>
     *
     * <p>The test issues a {@code GET} request via {@link MockMvc} to the URL
     * produced by {@code getBaseUrl(targetAccount.getId())} **without** supplying an
     * {@code Authorization} header. The expected assertion is that the response
     * status matches {@code status().isUnauthorized()}.</p>
     *
     * <p>Purpose: ensures the security configuration of
     * {@link StatementController} enforces authentication for the
     * {@code /api/v1/statements/export} resource, thereby protecting
     * statement data from unauthenticated access.</p>
     *
     * @see StatementController
     * @see StatementService
     * @see UserRepository
     * @see AccountRepository
     *
     */
    // Test 2
    @Test
    void test2_ExportStatement_NoAuthToken_Returns401Unauthorized() throws Exception {
        mockMvc.perform(get(getBaseUrl(targetAccount.getId())))
                .andExpect(status().isUnauthorized());
    }

    /**
     * <p>Ensures that a {@link User} who does not own the requested {@link Account}
     * cannot export the account's statement, thereby enforcing object‑level
     * authorization (BOLA). The test simulates an attacker by authenticating the
     * request with the {@code attacker} token and expects the endpoint to respond
     * with HTTP {@code 403 Forbidden}.</p>
     *
     * <p>The request is performed with {@link MockMvc} against the URL generated by
     * {@link #getBaseUrl(UUID)} using the {@code targetAccount}'s identifier.
     * No additional query parameters are altered; the default date range (last month
     * to today) is used.</p>
     *
     * @see StatementController
     * @see StatementService
     * @see UserRepository
     * @see AccountRepository
     */
    // Test 3
    @Test
    void test3_ExportStatement_BolaViolation_Returns403Forbidden() throws Exception {
        // The Attacker attempts to download the Owner's statement
        mockMvc.perform(get(getBaseUrl(targetAccount.getId()))
                        .with(authentication(createRealAuthToken(attacker))))
                .andExpect(status().isForbidden());
    }

    /**
     * Ensures that an administrator can override the standard role‑based access control (RBAC)
     * to download an account statement owned by another user and receive an HTTP {@code 200 OK}
     * response.
     *
     * <p>The test issues a {@code GET} request to the URL produced by
     * {@code getBaseUrl(targetAccount.getId())} while authenticating with a real token
     * representing the {@code admin} user. The expectation is that the request succeeds
     * with {@code status().isOk()}.
     *
     * @throws Exception if the {@code MockMvc} request execution encounters an error.
     *
     * @see StatementController
     * @see StatementService
     */
    // Test 4
    @Test
    void test4_ExportStatement_AdminOverride_Returns200OK() throws Exception {
        // The Admin successfully downloads the Owner's statement (RBAC Override)
        mockMvc.perform(get(getBaseUrl(targetAccount.getId()))
                        .with(authentication(createRealAuthToken(admin))))
                .andExpect(status().isOk());
    }


    // ==================================================================================
    // PHASE 3: INPUT VALIDATION & BOUNDARY DEFENSE
    // ==================================================================================

    /**
     * Verifies that the **export statements** endpoint returns a {@code 400 Bad Request}
     * response when a required query parameter is omitted.
     *
     * <p>This test targets the {@code /api/v1/statements/export} API, which requires both
     * {@code accountId}, {@code startDate}, and {@code endDate} parameters to generate an
     * export file. Supplying an incomplete request (missing {@code endDate}) should be
     * rejected by the controller validation layer, ensuring the API contract is enforced
     * and preventing downstream processing errors.
     *
     * <p>The test constructs a request URL containing {@code accountId} and {@code startDate}
     * only, performs the request with an authenticated user, and asserts that the HTTP
     * status code is {@link org.springframework.test.web.servlet.result.MockMvcResultMatchers#status()}
     * {@code isBadRequest()}.
     *
     * @throws Exception
     *         if an error occurs while performing the mock MVC request.
     *
     * @see StatementController
     * @see StatementService
     */
    // Test 5
    @Test
    void test5_ExportStatement_MissingQueryParameters_Returns400BadRequest() throws Exception {
        // Missing the 'endDate' parameter
        String invalidUrl = "/api/v1/statements/export?accountId=" + targetAccount.getId()
                + "&startDate=" + LocalDate.now().minusMonths(1);

        mockMvc.perform(get(invalidUrl)
                        .with(authentication(createRealAuthToken(owner))))
                .andExpect(status().isBadRequest());
    }

    /**
     * Verifies that exporting a statement with a {@code startDate} set in the future
     * results in an HTTP {@code 400 Bad Request} response.
     * <p>
     * The test constructs a request URL with {@code startDate}=2099-01-01 and
     * {@code endDate}=2099-01-31, which violates the {@code @PastOrPresent}
     * validation constraint defined on the DTO consumed by the export endpoint.
     * When the request is performed via {@link MockMvc}, the validation layer
     * rejects the input and the controller returns a {@code 400} status.
     * <p>
     * This ensures that clients cannot request statements for periods that have
     * not yet occurred, preserving data integrity and preventing unnecessary
     * processing on the server side.
     *
     * @see StatementController
     * @see StatementService
     */
    // Test 6
    @Test
    void test6_ExportStatement_FutureStartDate_Returns400BadRequest() throws Exception {
        String invalidUrl = "/api/v1/statements/export?accountId=" + targetAccount.getId()
                + "&startDate=2099-01-01"
                + "&endDate=2099-01-31";

        // Triggers the @PastOrPresent validation constraint on the DTO
        mockMvc.perform(get(invalidUrl)
                        .with(authentication(createRealAuthToken(owner))))
                .andExpect(status().isBadRequest());
    }

    /**
     * Ensures that the statement export endpoint rejects a request when the
     * supplied date range spans more than six months, returning HTTP {@code 400 Bad Request}.
     * <p>
     * The test builds an invalid URL by specifying a {@code startDate} that is ten
     * months prior to {@code LocalDate.now()} and an {@code endDate} equal to today.
     * This violates the business rule performed by {@link com.opayque.api.statement.dto.StatementExportRequest#validateDateRange(int)}
     * (which is invoked inside the controller layer).  The request is executed with
     * {@link MockMvc} under the authentication context of the account owner, and the
     * expectation asserts that the response status is {@code 400 Bad Request}.
     * </p>
     *
     * @see StatementController
     * @see StatementService
     */
    // Test 7
    @Test
    void test7_ExportStatement_DateRangeExceedsSixMonths_Returns400BadRequest() throws Exception {
        String invalidUrl = "/api/v1/statements/export?accountId=" + targetAccount.getId()
                + "&startDate=" + LocalDate.now().minusMonths(10)
                + "&endDate=" + LocalDate.now();

        // Triggers the internal DTO validateDateRange() business logic check
        mockMvc.perform(get(invalidUrl)
                        .with(authentication(createRealAuthToken(owner))))
                .andExpect(status().isBadRequest());
    }

    // ==================================================================================
    // PHASE 4: RATE LIMITING (REAL REDIS INTEGRATION)
    // ==================================================================================

    /**
     * Test case that verifies the export‑statement endpoint respects the configured
     * rate‑limit. It issues the maximum allowed number of requests within a minute
     * and then asserts that a subsequent request receives HTTP 429 (Too Many
     * Requests).<p>
     *
     * Purpose: Ensures that the {@link com.opayque.api.infrastructure.ratelimit.RateLimiterService} correctly invokes the Redis
     * Lua script to block traffic that exceeds the quota, thereby protecting the
     * service from abuse.<p>
     *
     * Test steps:
     * <ul>
     *   <li>Construct the request URL for {@code targetAccount} using {@code getBaseUrl}.</li>
     *   <li>Perform {@code 5} successful {@code GET} requests, which represent the
     *       defined maximum quota.</li>
     *   <li>Perform a sixth {@code GET} request within the same minute and expect a
     *       {@code 429 Too Many Requests} response.</li>
     * </ul>
     *
     * @see StatementController
     * @see StatementService
     */
    // Test 8
    @Test
    void test8_ExportStatement_RateLimitExceeded_Returns429TooManyRequests() throws Exception {
        String url = getBaseUrl(targetAccount.getId());

        // Fire 5 identical valid requests (Max Quota)
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get(url).with(authentication(createRealAuthToken(owner))))
                    .andExpect(status().isOk());
        }

        // The 6th request within the same minute MUST trigger the Redis Lua script block
        mockMvc.perform(get(url).with(authentication(createRealAuthToken(owner))))
                .andExpect(status().isTooManyRequests());
    }

    // ==================================================================================
    // PHASE 5: THE HAPPY PATH & STREAM PAGINATION
    // ==================================================================================

    
    /**
     * Verifies that the statement export endpoint returns a valid CSV response
     * with the correct HTTP headers and content for a standard date range.
     * <p>
     * The test performs the following steps:
     * <ul>
     *   <li>Creates a {@link LedgerEntry} instance with a positive amount, currency
     *       {@code EUR}, direction {@code IN}, and a description of
     *       {@code "Salary Deposit"}.</li>
     *   <li>Saves the entry using {@link LedgerRepository}.</li>
     *   <li>Executes a {@code GET} request against the export URL for
     *       {@code targetAccount} with a real authentication token.</li>
     *   <li>Asserts that the response:
     *       <ul>
     *         <li>has HTTP status {@code 200 OK}</li>
     *         <li>contains the header {@code Content-Type} with value
     *             {@code text/csv; charset=utf-8}</li>
     *         <li>includes a {@code Content‑Disposition} header (indicating a file
     *             attachment)</li>
     *         <li>includes the security header {@code X-Content-Type-Options}
     *             set to {@code nosniff}</li>
     *         <li>contains CSV rows with the description {@code "Salary Deposit"},
     *             the formatted amount {@code 150.0000}, and the currency
     *             {@code EUR}</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * @see LedgerRepository
     * @see StatementController
     */
    // Test 9
    @Test
    void test9_ExportStatement_StandardDateRange_ReturnsValidCsvAndHeaders() throws Exception {
        com.opayque.api.wallet.entity.LedgerEntry entry = com.opayque.api.wallet.entity.LedgerEntry.builder()
                .account(targetAccount)
                .amount(new java.math.BigDecimal("150.00"))
                .currency("EUR")
                .direction("IN")
                .transactionType(com.opayque.api.wallet.entity.TransactionType.CREDIT)
                .description("Salary Deposit")
                .recordedAt(java.time.LocalDateTime.now().minusDays(2))
                .build();
        ledgerRepository.save(entry);

        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(get(getBaseUrl(targetAccount.getId()))
                        .with(authentication(createRealAuthToken(owner))))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Content-Type", "text/csv; charset=utf-8"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().exists("Content-Disposition"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("Salary Deposit"));
        assertTrue(content.contains("150.0000"));
        assertTrue(content.contains("EUR"));
    }

    /**
     * Integration test that verifies the export‑statement endpoint can stream a
     * massive ledger without causing an {@code OutOfMemoryError}.
     *
     * <p>Created by Madavan Babu, 2026.</p>
     *
     * <p>The test inserts 1,200 {@link LedgerEntry}
     * records for the {@code targetAccount}. This size exceeds the default slice
     * size (500) used by {@link LedgerRepository}, causing the repository to return
     * the data in at least three separate chunks. The controller therefore streams
     * the paginated data to the HTTP client while handling each chunk efficiently.</p>
     *
     * <p>Assertions performed:
     * <ul>
     *   <li>The streamed CSV contains at least 1,200 lines, ensuring no records are
     *   omitted across chunk boundaries.</li>
     *   <li>The first and last transaction descriptions
     *       {@code "Bulk Streaming Tx 0"} and {@code "Bulk Streaming Tx 1199"} are
     *       present in the response content.</li>
     * </ul>
     * </p>
     *
     * <p>This test validates the collaboration between Spring Data {@code Slice}
     * pagination, the streaming response configuration in
     * {@link LedgerRepository}, and the memory‑efficient processing in
     * {@link LedgerService}.</p>
     *
     * @see LedgerService
     * @see LedgerRepository
     */
    // Test 10
    @Test
    void test10_ExportStatement_MassiveLedger_StreamsMultipleChunksWithoutOOM() throws Exception {
        java.util.List<com.opayque.api.wallet.entity.LedgerEntry> bulkEntries = new java.util.ArrayList<>();
        java.time.LocalDateTime baseTime = java.time.LocalDateTime.now().minusDays(10);

        // Insert 1,200 records to force the repository Slice logic to chunk at least 3 times (500 per chunk)
        for (int i = 0; i < 1200; i++) {
            bulkEntries.add(com.opayque.api.wallet.entity.LedgerEntry.builder()
                    .account(targetAccount)
                    .amount(java.math.BigDecimal.TEN)
                    .currency("GBP")
                    .direction("OUT")
                    .transactionType(com.opayque.api.wallet.entity.TransactionType.DEBIT)
                    .description("Bulk Streaming Tx " + i)
                    .recordedAt(baseTime.plusMinutes(i))
                    .build());
        }
        ledgerRepository.saveAll(bulkEntries);

        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(get(getBaseUrl(targetAccount.getId()))
                        .with(authentication(createRealAuthToken(owner))))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        String[] lines = content.split("\n");

        // Asserts we didn't drop records across chunk boundaries
        assertTrue(lines.length >= 1200, "Stream dropped records during pagination chunks.");
        assertTrue(content.contains("Bulk Streaming Tx 0"));
        assertTrue(content.contains("Bulk Streaming Tx 1199"));
    }

    // ==================================================================================
    // PHASE 6: DATA INTEGRITY & ATTACK MITIGATION (END-TO-END)
    // ==================================================================================

    /**
     * <p>Verifies that a CSV export of ledger statements neutralizes an Excel
     * formula‑injection payload that is persisted in the database.</p>
     *
     * <p>The test stores a {@link LedgerEntry} whose {@code description}
     * contains a malicious Excel formula prefixed with a plus sign
     * (<code>+SUM(A1:A10)</code>). When the ledger data is retrieved via the
     * export endpoint, the response must retain only the formula body
     * (<code>SUM(A1:A10)</code>) and must strip the leading plus character,
     * which spreadsheet applications interpret as a formula trigger. This
     * behaviour mitigates CSV injection attacks.</p>
     *
     * <p>Test workflow:</p>
     * <ul>
     *   <li>Create and save a {@link LedgerEntry} with a dangerous
     *       {@code description} value.</li>
     *   <li>Perform a {@code GET} request to the export endpoint using
     *       {@link MockMvc} authenticated as the account owner.</li>
     *   <li>Assert that the response payload contains the sanitized formula
     *       text and does not contain the original dangerous prefix.</li>
     * </ul>
     *
     * @throws Exception if the request execution or the assertions fail.
     *
     * @see LedgerService
     * @see LedgerRepository
     */
    // Test 11
    @Test
    void test11_ExportStatement_CsvInjectionPayloadInDb_NeutralizedInOutput() throws Exception {
        com.opayque.api.wallet.entity.LedgerEntry entry = com.opayque.api.wallet.entity.LedgerEntry.builder()
                .account(targetAccount)
                .amount(java.math.BigDecimal.ONE)
                .currency("CHF")
                .direction("IN")
                .transactionType(com.opayque.api.wallet.entity.TransactionType.CREDIT)
                .description("+SUM(A1:A10)") // Malicious Excel formula
                .recordedAt(java.time.LocalDateTime.now().minusDays(1))
                .build();
        ledgerRepository.save(entry);

        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(get(getBaseUrl(targetAccount.getId()))
                        .with(authentication(createRealAuthToken(owner))))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertTrue(content.contains("SUM(A1:A10)"), "Formula body should exist");
        assertFalse(content.contains("+SUM(A1:A10)"), "Dangerous prefix (+) leaked into CSV export");
    }


    /**
     * <p>
     * Integration test that validates the export‑statement endpoint behaves as a
     * jurisdictional gatekeeper by omitting ledger entries that use an unsupported
     * currency.
     * </p>
     *
     * <p>
     * The test deliberately inserts a rogue record directly via
     * {@link JdbcTemplate} to bypass any JPA validation
     * and simulate a legacy data corruption scenario. The record is inserted with
     * currency {@code "USD"} which is not permitted for export in the current
     * jurisdiction configuration.
     * </p>
     *
     * <p>
     * After the insertion, the test issues a {@code GET} request to the export API
     * using {@link MockMvc}. The response is
     * inspected to ensure that:
     * </p>
     * <ul>
     *   <li>The description of the rogue transaction ("Rogue USD Transaction") is not present.</li>
     *   <li>The unsupported currency code {@code "USD"} does not appear anywhere
     *   in the exported payload.</li>
     * </ul>
     *
     * <p>
     * This guarantees that the service enforces currency constraints at the
     * presentation layer, preventing corrupted or out‑of‑policy data from leaking
     * to downstream consumers.
     * </p>
     *
     * @throws Exception if an error occurs while performing the HTTP request or
     *         interacting with the database.
     *
     * @see StatementController
     * @see StatementService
     * @see LedgerRepository
     */
    // Test 12
    @Test
    void test12_ExportStatement_RogueCurrencyInDb_OmittedFromStream() throws Exception {
        // We use native JdbcTemplate to bypass any application-level JPA @Valid constraints
        // to physically force an unsupported currency into the DB simulating a legacy system corruption.
        jdbcTemplate.update("INSERT INTO ledger_entries (id, account_id, amount, currency, direction, transaction_type, description, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), targetAccount.getId(), new java.math.BigDecimal("999.00"), "USD", "IN", "CREDIT", "Rogue USD Transaction", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusDays(1)));

        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(get(getBaseUrl(targetAccount.getId()))
                        .with(authentication(createRealAuthToken(owner))))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        // The service must act as a Jurisdiction Gatekeeper and drop the USD row entirely
        assertFalse(content.contains("Rogue USD Transaction"), "Rogue entry bypassed jurisdiction gatekeeper");
        assertFalse(content.contains("USD"), "Unsupported currency (USD) leaked into export");
    }

    /**
     * <p>Validates that personally identifiable information (PII) is correctly masked when a
     * statement is exported via a streamed HTTP response header.</p>
     *
     * <p>The test performs a {@code GET} request to the statement export endpoint
     * for a specific account, authenticates as the account owner, and expects an HTTP
     * 200 (OK) status. After the request completes, the response body is inspected to ensure:</p>
     *
     * <ul>
     *   <li>The IBAN is present in a masked form (<em>e.g.</em>, {@code "DE30 **** **** 5678"}) in the
     *       header content, demonstrating that the masking logic has been applied.</li>
     *   <li>The raw, unmasked IBAN (<em>e.g.</em>, {@code "DE30123456789012345678"}) does <strong>not</strong>
     *       appear anywhere in the response, guaranteeing that no sensitive data leaks into the
     *       exported stream.</li>
     * </ul>
     *
     * <p>This test ensures compliance with data protection requirements by confirming that
     * the {@code PIIMasking} component is integrated into the streaming layer that serves
     * statement data.</p>
     *
     * @see MockMvc
     * @see MockMvcResultMatchers
     * @see org.springframework.security.core.Authentication
     */
    // Test 13
    @Test
    void test13_ExportStatement_PIIMasking_AppliedToStreamedHeader() throws Exception {
        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(get(getBaseUrl(targetAccount.getId()))
                        .with(authentication(createRealAuthToken(owner))))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        // The raw IBAN in DB is DE30123456789012345678
        assertTrue(content.contains("Account IBAN,DE30 **** **** 5678"));
        assertFalse(content.contains("DE30123456789012345678"), "Unmasked IBAN leaked into PII header");
    }

    /**
     * <p>Integration test that verifies the export statement endpoint formats numeric
     * values with the correct decimal precision when streamed to the HTTP response
     * body.</p>
     *
     * <p>The test creates a {@link LedgerEntry} with deliberately high‑precision
     * {@code BigDecimal} fields:
     * <ul>
     *   <li>{@code amount} = {@code 100.5} – expected to be rendered as {@code 100.5000}
     *       (scale 4)</li>
     *   <li>{@code originalAmount} = {@code 120.3333333} – expected to be rendered as
     *       {@code 120.3333} (scale 4)</li>
     *   <li>{@code exchangeRate} = {@code 0.8543219} – expected to be rendered as
     *       {@code 0.854322} (scale 6, rounded)</li>
     * </ul>
     * These values are persisted via the {@link LedgerRepository} and then retrieved
     * through the API using {@link MockMvc}. The response content is examined as a raw
     * string to ensure the precision rules are applied consistently, independent of any
     * JSON deserialization.</p>
     *
     * <p>This validation is critical for downstream financial processing systems,
     * which rely on a fixed number of decimal places for accurate currency handling
     * and reconciliation.</p>
     *
     * @see LedgerEntry
     * @see LedgerRepository
     * @see MockMvc
     */
    // Test 14
    @Test
    void test14_ExportStatement_PrecisionFormatting_AppliedToStreamedBody() throws Exception {
        com.opayque.api.wallet.entity.LedgerEntry entry = com.opayque.api.wallet.entity.LedgerEntry.builder()
                .account(targetAccount)
                .amount(new java.math.BigDecimal("100.5")) // Will become 100.5000
                .currency("EUR")
                .direction("IN")
                .transactionType(com.opayque.api.wallet.entity.TransactionType.CREDIT)
                .description("Forex Conversion")
                .originalAmount(new java.math.BigDecimal("120.3333333")) // Will become 120.3333
                .exchangeRate(new java.math.BigDecimal("0.8543219")) // Will become 0.854322
                .recordedAt(java.time.LocalDateTime.now().minusDays(1))
                .build();
        ledgerRepository.save(entry);

        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(get(getBaseUrl(targetAccount.getId()))
                        .with(authentication(createRealAuthToken(owner))))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();

        // Asserting Scale 4 and Scale 6 requirements explicitly in the raw text stream
        assertTrue(content.contains("100.5000"));
        assertTrue(content.contains("120.3333"));
        assertTrue(content.contains("0.854322"));
    }

    /**
     * <p>Ensures that {@link StatementController#exportStatement(com.opayque.api.statement.dto.StatementExportRequest, jakarta.servlet.http.HttpServletResponse)}
     * correctly handles a client‑side disconnection scenario. The test simulates a broken TCP pipe by configuring a mocked
     * {@link jakarta.servlet.http.HttpServletResponse} to throw an {@link java.io.IOException} when {@code getWriter()} is invoked.
     * It then verifies that the controller's catch block logs the error and re‑throws the original exception, preserving the
     * original message ("Connection reset by peer").</p>
     *
     * <p>This validation is crucial for robust export functionality, as network interruptions are common when streaming
     * large statement files to remote clients. By guaranteeing that the controller does not silently swallow the exception,
     * downstream error handling and monitoring mechanisms remain reliable.</p>
     *
     * @see StatementController
     * @see com.opayque.api.statement.dto.StatementExportRequest
     * @see StatementService
     */
    // Test 15
    @Test
    void test15_ExportStatement_ClientDisconnect_TriggersIOExceptionCatchBlock() throws Exception {
        // 1. Arrange: Mock the HttpServletResponse to simulate a severed TCP connection (Broken Pipe)
        jakarta.servlet.http.HttpServletResponse mockResponse = org.mockito.Mockito.mock(jakarta.servlet.http.HttpServletResponse.class);

        // When the controller tries to open the stream, we forcefully simulate a network drop
        org.mockito.Mockito.when(mockResponse.getWriter()).thenThrow(new java.io.IOException("Connection reset by peer"));

        com.opayque.api.statement.dto.StatementExportRequest request =
                new com.opayque.api.statement.dto.StatementExportRequest(
                        targetAccount.getId(), LocalDate.now().minusMonths(1), LocalDate.now());

        // We must manually set the SecurityContext since we are bypassing MockMvc's filter chain
        SecurityContextHolder.getContext().setAuthentication(createRealAuthToken(owner));

        // 2. Act & Assert: The controller must catch the IOException, log it, and re-throw it.
        java.io.IOException ex = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.IOException.class,
                () -> statementController.exportStatement(request, mockResponse),
                "Controller failed to properly catch and re-throw the network IOException"
        );

        // Verify the exact exception passed through the catch block
        assertTrue(ex.getMessage().contains("Connection reset by peer"));
    }
}