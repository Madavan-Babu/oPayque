package com.opayque.api.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.admin.dto.AccountStatusUpdateRequest;
import com.opayque.api.admin.dto.MoneyDepositRequest;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.identity.service.JwtService;
import com.opayque.api.transactions.service.TransferService;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
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

    @Test
    @DisplayName("Security: Unauthenticated request should be rejected (401)")
    void unauthenticatedCannotAccess() throws Exception {
        mockMvc.perform(patch("/api/v1/admin/accounts/{id}/status", activeAccount.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AccountStatusUpdateRequest(AccountStatus.FROZEN))))
                .andExpect(status().isUnauthorized());
    }

    // --- SCENARIO 3: LOGIC & STATE MACHINE ---

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