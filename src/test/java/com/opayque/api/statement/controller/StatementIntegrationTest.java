package com.opayque.api.statement.controller;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
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

    // Test 2
    @Test
    void test2_ExportStatement_NoAuthToken_Returns401Unauthorized() throws Exception {
        mockMvc.perform(get(getBaseUrl(targetAccount.getId())))
                .andExpect(status().isUnauthorized());
    }

    // Test 3
    @Test
    void test3_ExportStatement_BolaViolation_Returns403Forbidden() throws Exception {
        // The Attacker attempts to download the Owner's statement
        mockMvc.perform(get(getBaseUrl(targetAccount.getId()))
                        .with(authentication(createRealAuthToken(attacker))))
                .andExpect(status().isForbidden());
    }

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