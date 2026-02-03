package com.opayque.api.wallet.controller;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection; // Spring Boot 3.1+
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
/// **Full-Slice Integration Audit: Wallet & Ledger Domain**.
///
/// This suite validates the complete operational chain from the REST boundary to the physical
/// database. It serves as the ultimate proof-of-work for the **"Magic Ledger"** system,
/// ensuring that the orchestration of entities, repositories, and services results in
/// accurate, real-time financial data.
///
/// **Architectural Scope:**
/// * **End-to-End Validation:** Exercises the `Controller -> Service -> Repository -> PostgreSQL` flow.
/// * **High-Fidelity Environment:** Utilizes [Testcontainers] to mirror production PostgreSQL
///   behavior, specifically testing the complex JPQL aggregation logic.
/// * **Security Integration:** Validates that authenticated contexts ([WithMockUser]) correctly
///   interface with the BOLA-protected wallet endpoints.
///
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class WalletViewIntegrationTest {

    /// **Ephemeral Infrastructure Orchestration**.
    ///
    /// Provisions a production-grade PostgreSQL 15 instance for the duration of the test suite.
    /// By using [@ServiceConnection], the system automatically binds JDBC credentials, ensuring
    /// the integration layer tests against a real RDBMS rather than an in-memory substitute.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    /// **High-Precision Dialect & Schema Configuration**.
    ///
    /// Injects runtime properties to bridge the gap between Spring's default testing behavior
    /// and the project's **"Postgres-First"** mandate.
    ///
    /// **Key Overrides:**
    /// - **Driver/Dialect:** Forces PostgreSQL semantics to validate native features like UUIDs
    ///   and advanced math functions.
    /// - **DDL Strategy:** Temporarily utilizes `create-drop` to initialize the schema
    ///   without the overhead of full Liquibase migrations for this specific slice.
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // 1. Your Standard Overrides (Driver & Dialect)
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // 2. THE CRITICAL FIX FOR THIS TEST,
        // Since we are creating fresh entities (User, Account) and ignoring Liquibase for speed,
        // we MUST tell Hibernate to create the schema.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.liquibase.enabled", () -> "false");

        // 3. Safety Valve
        // If the "enum" error persists, this line ensures no external schema.sql interferes.
        registry.add("spring.sql.init.mode", () -> "never");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private LedgerRepository ledgerRepository;

    /// **Database Sanitization & Isolation**.
    ///
    /// Executes a comprehensive purge of the identity and wallet tables in reverse
    /// Foreign Key order. This ensures a "Clean Slate" for every test case, preventing
    /// balance leakage or identity collisions between transactional windows.
    @BeforeEach
    void cleanSlate() {
        // Clear data in reverse FK order
        ledgerRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    /// **Epic 2 Audit: Real-Time Dynamic Balance Aggregation**.
    ///
    /// Validates the core mathematical requirement of the digital wallet: deriving an
    /// accurate balance from a stream of immutable ledger entries.
    ///
    /// **Scenario:**
    /// 1. Provisions a user and two multi-currency wallets (USD/EUR).
    /// 2. Injects a complex history of Credits and Debits.
    /// 3. Asserts that the REST response matches the zero-sum aggregate of the entries.
    ///
    /// **Invariants Tested:**
    /// - **USD Balance:** 100 (Cr) - 20 (Dr) + 50 (Cr) = 130.00.
    /// - **EUR Balance:** 500 (Cr) - 500 (Dr) = 0.00.
    ///
    @Test
    @DisplayName("Dashboard: Should aggregate ledger entries into correct real-time balances")
    @WithMockUser(username = "alice@opayque.com")
    void shouldReturnAggregatedDashboard() throws Exception {
        // --- 1. SETUP: Create the User (Alice) ---
        User alice = userRepository.save(User.builder()
                .email("alice@opayque.com")
                .fullName("Alice Wonderland")
                .password("hashed_secret")
                .role(Role.CUSTOMER)
                .build());

        // --- 2. SETUP: Create Wallets (USD & EUR) ---
        Account usdWallet = accountRepository.save(Account.builder()
                .user(alice)
                .currencyCode("USD")
                .iban("US00000001")
                .build());

        Account eurWallet = accountRepository.save(Account.builder()
                .user(alice)
                .currencyCode("EUR")
                .iban("DE00000002")
                .build());

        // --- 3. SETUP: Inject Ledger History ---

        // USD: 100 - 20 + 50 = 130.00
        injectEntry(usdWallet, "100.00", TransactionType.CREDIT);
        injectEntry(usdWallet, "20.00",  TransactionType.DEBIT);
        injectEntry(usdWallet, "50.00",  TransactionType.CREDIT);

        // EUR: 500 - 500 = 0.00
        injectEntry(eurWallet, "500.00", TransactionType.CREDIT);
        injectEntry(eurWallet, "500.00", TransactionType.DEBIT);

        // --- 4. EXECUTE & ASSERT ---
        mockMvc.perform(get("/api/v1/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[?(@.account.currencyCode == 'USD')].balance").value(130.0))
                .andExpect(jsonPath("$[?(@.account.currencyCode == 'EUR')].balance").value(0.0));
    }

    /// **Low-Level Transactional Seed Engine**.
    ///
    /// Directly persists [LedgerEntry] entities into the database to simulate historical
    /// financial activity. It bypasses the Service layer validation to allow for high-precision
    /// data shaping required for edge-case testing.
    ///
    /// @param account The target [Account] for the entry.
    /// @param amountStr The financial value, parsed as [BigDecimal].
    /// @param type The classification of movement (CREDIT/DEBIT).
    private void injectEntry(Account account, String amountStr, TransactionType type) {
        ledgerRepository.save(LedgerEntry.builder()
                .account(account)
                .amount(new BigDecimal(amountStr))
                .transactionType(type)
                .currency(account.getCurrencyCode())
                .direction(type == TransactionType.CREDIT ? "IN" : "OUT")
                .description("Integration Seed")
                .recordedAt(LocalDateTime.now())
                // Fields required by @Column(nullable = false)
                .originalAmount(new BigDecimal(amountStr))
                .originalCurrency(account.getCurrencyCode())
                .exchangeRate(BigDecimal.ONE)
                .build());
    }
}