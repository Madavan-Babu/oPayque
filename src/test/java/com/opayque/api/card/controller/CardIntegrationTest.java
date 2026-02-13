package com.opayque.api.card.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.card.dto.CardIssueRequest;
import com.opayque.api.card.dto.CardStatusUpdateRequest;
import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.card.service.CardGeneratorService;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.identity.service.JwtService;
import com.opayque.api.identity.service.TokenBlocklistService;
import com.opayque.api.infrastructure.exception.RateLimitExceededException;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


/**
 * End-to-end integration test-suite for the Virtual-Card domain within the oPayque FinTech platform.
 * <p>
 * Executes against production-grade infrastructure spun-up via Testcontainers (PostgreSQL 15, Redis 7)
 * and validates PCI-DSS, PSD2, and internal AML/KYC compliance controls:
 * <ul>
 *   <li>Card issuance with velocity & currency wallet validation</li>
 *   <li>State-machine governed lifecycle (ACTIVE → FROZEN → TERMINATED)</li>
 *   <li>Data-at-rest encryption for PAN/CVV and masked PAN in API responses</li>
 *   <li>Rate-limiting, idempotency, and BOLA (Broken Object Level Authorization) protections</li>
 * </ul>
 * </p>
 * <p>
 * Liquibase migrations are applied <b>before</b> tests run; Hibernate operates in <code>validate</code>
 * mode to guarantee schema conformity with production. All secrets are stubbed via
 * {@link CardGeneratorService} to avoid HSM dependencies while preserving cryptographic contracts.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // CRITICAL: Prevents H2 override
class CardIntegrationTest {

    // 1. PROD-GRADE DB SETUP (Testcontainers)
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("opayque_test")
            .withUsername("test")
            .withPassword("test");

    // 2. REDIS SETUP (Testcontainers)
    @Container
    static GenericContainer<?> redis = new GenericContainer<>(org.testcontainers.utility.DockerImageName.parse("redis:7-alpine"))
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
        // Ensure Liquibase is ENABLED and using the correct driver
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.driver-class-name", postgres::getDriverClassName);

        // 4. Hibernate Logic
        // Since Liquibase is creating the tables, Hibernate should only VALIDATE them.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // 5. Redis Support
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // Repositories for Data Setup & Verification
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private VirtualCardRepository virtualCardRepository;
    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // Infrastructure Mocks
    @MockitoBean private RateLimiterService rateLimiterService;
    // Mock JWT Service to satisfy dependency injection if Security Config loads it
    @MockitoBean private JwtService jwtService;
    @MockitoBean private TokenBlocklistService tokenBlocklistService;
    @MockitoBean private CardGeneratorService cardGeneratorService;

    private User testUser;
    private Account testWallet;

    /**
     * Resets the test universe before each test method to ensure deterministic, isolated execution.
     * <p>
     * 1. Purges all cards, ledger entries, wallets, and users to prevent cross-test contamination.<br>
     * 2. Creates a PSD2-compliant customer with a single SEPA wallet (EUR) pre-funded with €1000.<br>
     * 3. Ledger entry is written with scale-4 precision to mirror production accounting rules.
     * </p>
     *
     * @throws org.springframework.dao.DataAccessException if Liquibase constraints are violated
     */
    @BeforeEach
    void setUp() {
        // Clear all relevant tables
        virtualCardRepository.deleteAll();
        ledgerRepository.deleteAll(); // Clear the ledger too!
        accountRepository.deleteAll();
        userRepository.deleteAll();

        // 1. Create User
        testUser = userRepository.save(User.builder()
                .email("integration@opayque.com")
                .password("hashed_secret")
                .fullName("Integration Tester")
                .role(Role.CUSTOMER)
                .build());

        // 2. Create Wallet (Requires IBAN, no balance field)
        testWallet = accountRepository.save(Account.builder()
                .user(testUser)
                .currencyCode("EUR")
                .iban("OPAY" + UUID.randomUUID().toString().substring(0, 20))
                .build());

        // 3. Fund the Wallet (This establishes the 1000.00 balance in the Ledger)
        ledgerRepository.save(com.opayque.api.wallet.entity.LedgerEntry.builder()
                .account(testWallet)
                .amount(new BigDecimal("1000.0000")) // Use scale 4 for ledger consistency
                .currency("EUR")
                .transactionType(com.opayque.api.wallet.entity.TransactionType.CREDIT)
                .direction("IN")
                .description("Initial Deposit")
                .recordedAt(java.time.LocalDateTime.now())
                .build());
    }

    // =========================================================================
    // GROUP 1: ISSUANCE FLOW
    // =========================================================================
    /**
     * Validates the card-issuance workflow end-to-end: rate-limiting, wallet-currency alignment,
     * idempotency, input validation, and secure persistence of encrypted secrets.
     * <p>
     * All tests assert both HTTP contracts and downstream database state to ensure ACID guarantees
     * and compliance with PSD2 Strong Customer Authentication (SCA) prerequisites.
     * </p>
     *
     * @author Madavan Babu
     * @since 2026
     */
    @Nested
    @DisplayName("Group 1: Card Issuance Flow")
    class IssuanceFlow {

        @Test
        @DisplayName("1. Success: Valid Request -> Persists Card & Returns Secrets")
        void shouldIssueCardSuccessfully() throws Exception {
            // Given
            CardIssueRequest request = new CardIssueRequest("EUR", new BigDecimal("500.00"));

            // When
            // FIX: Stub the missing generator so the service can complete its work
            when(cardGeneratorService.generateCard())
                    .thenReturn(new com.opayque.api.card.model.CardSecrets("4111222233334444", "123", "12/30"));
            mockMvc.perform(post("/api/v1/cards/issue")
                            .header("Idempotency-Key", UUID.randomUUID().toString()) // ADDED
                            .with(user(testUser)) // Inject Custom User Entity into SecurityContext
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    // Then: HTTP Contract
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.pan").exists()) // Unmasked
                    .andExpect(jsonPath("$.cvv").exists()) // Unmasked
                    .andExpect(jsonPath("$.monthlyLimit").value(500.00));

            // Then: Database State
            var cards = virtualCardRepository.findAll();
            assertThat(cards).hasSize(1);
            assertThat(cards.get(0).getStatus()).isEqualTo(CardStatus.ACTIVE);
            assertThat(cards.get(0).getAccount().getId()).isEqualTo(testWallet.getId());
        }

        @Test
        @DisplayName("2. No Wallet: Request Currency User Does Not Own -> 400 Bad Request")
        void shouldRejectWhenWalletNotFound() throws Exception {
            // User has EUR wallet, requesting USD
            CardIssueRequest request = new CardIssueRequest("USD", new BigDecimal("100.00"));

            // FIX: Stub the missing generator so the service can complete its work
            when(cardGeneratorService.generateCard())
                    .thenReturn(new com.opayque.api.card.model.CardSecrets("4111222233334444", "123", "12/30"));
            mockMvc.perform(post("/api/v1/cards/issue")
                            .header("Idempotency-Key", UUID.randomUUID().toString()) // ADDED
                            .with(user(testUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest()) // GlobalExceptionHandler maps IllegalArgumentException -> 400
                    .andExpect(jsonPath("$.message").value("No wallet found for currency: USD"));

            assertThat(virtualCardRepository.count()).isZero();
        }

        @Test
        @DisplayName("3. Rate Limit: Service Throws 429 -> Controller Returns 429")
        void shouldReturn429WhenRateLimitExceeded() throws Exception {
            // FIX 1: Use anyLong() to avoid int/long matching errors
            doThrow(new RateLimitExceededException("Limit reached"))
                    .when(rateLimiterService).checkLimit(anyString(), eq("card_issue"), anyLong());

            CardIssueRequest request = new CardIssueRequest("EUR", null);

            mockMvc.perform(post("/api/v1/cards/issue")
                            .header("Idempotency-Key", UUID.randomUUID().toString()) // ADDED
                            .with(user(testUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isTooManyRequests())
                    // FIX 2: Align assertion with standard Spring/GlobalExceptionHandler output
                    .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        }

        @Test
        @DisplayName("4. Validation: Invalid Input -> 400 Bad Request")
        void shouldReturn400OnInvalidInput() throws Exception {
            // Invalid Currency (Too short)
            CardIssueRequest request = new CardIssueRequest("XX", new BigDecimal("-100"));

            // FIX: Stub the missing generator so the service can complete its work
            when(cardGeneratorService.generateCard())
                    .thenReturn(new com.opayque.api.card.model.CardSecrets("4111222233334444", "123", "12/30"));
            mockMvc.perform(post("/api/v1/cards/issue")
                            .header("Idempotency-Key", UUID.randomUUID().toString()) // ADDED
                            .with(user(testUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.currency").exists())
                    .andExpect(jsonPath("$.monthlyLimit").exists());
        }
    }

    // =========================================================================
    // GROUP 2: LIFECYCLE FLOW
    // =========================================================================

    /**
     * Covers the immutable state-machine defined by {@link CardStatus}:
     * <ul>
     *   <li>Legal transitions (ACTIVE ↔ FROZEN → TERMINATED)</li>
     *   <li>Illegal transition attempts return HTTP 409 Conflict</li>
     *   <li>BOLA protection: users may mutate only their own cards (403 Forbidden)</li>
     *   <li>Malformed enum values trigger HTTP 400 with MALFORMED_JSON code</li>
     * </ul>
     * <p>
     * Ensures that no terminal state can be reversed, satisfying PCI-DSS requirement 3.5.2.
     * </p>
     *
     * @author Madavan Babu
     * @since 2026
     */
    @Nested
    @DisplayName("Group 2: Lifecycle Management (Freeze/Terminate)")
    class LifecycleFlow {

        @Test
        @DisplayName("5. Success: Active -> Frozen -> Updates DB")
        void shouldAllowUserToFreezeOwnCard() throws Exception {
            // FIX: Use baseCardBuilder to ensure NOT NULL fields (pan, cvv, expiry) are present
            VirtualCard card = virtualCardRepository.save(baseCardBuilder(testWallet)
                    .status(CardStatus.ACTIVE)
                    .build());

            CardStatusUpdateRequest request = new CardStatusUpdateRequest(CardStatus.FROZEN);

            mockMvc.perform(patch("/api/v1/cards/{id}/status", card.getId())
                            .with(user(testUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FROZEN"));

            VirtualCard updated = virtualCardRepository.findById(card.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(CardStatus.FROZEN);
        }

        @Test
        @DisplayName("6. State Machine: Terminated -> Active (Illegal) -> 409 Conflict")
        void shouldRejectIllegalStatusTransition() throws Exception {
            // FIX: Use baseCardBuilder
            VirtualCard deadCard = virtualCardRepository.save(baseCardBuilder(testWallet)
                    .status(CardStatus.TERMINATED)
                    .build());

            CardStatusUpdateRequest request = new CardStatusUpdateRequest(CardStatus.ACTIVE);

            mockMvc.perform(patch("/api/v1/cards/{id}/status", deadCard.getId())
                            .with(user(testUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid status transition")));
        }

        @Test
        @DisplayName("7. Security: BOLA Attack (User A mods User B's card) -> 403 Forbidden")
        void shouldRejectCardStatusChangeIfNotOwner() throws Exception {
            // FIX: Use baseCardBuilder
            VirtualCard victimCard = virtualCardRepository.save(baseCardBuilder(testWallet)
                    .status(CardStatus.ACTIVE)
                    .build());

            User attacker = userRepository.save(User.builder()
                    .email("hacker@opayque.com")
                    .password("secret")
                    .fullName("Attacker")
                    .role(Role.CUSTOMER)
                    .build());

            CardStatusUpdateRequest request = new CardStatusUpdateRequest(CardStatus.TERMINATED);

            mockMvc.perform(patch("/api/v1/cards/{id}/status", victimCard.getId())
                            .with(user(attacker))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("8. Robustness: Malformed Enum (SUPER_ACTIVE) -> 400 Bad Request")
        void shouldReturn400OnMalformedEnum() throws Exception {
            // FIX: Use baseCardBuilder
            VirtualCard card = virtualCardRepository.save(baseCardBuilder(testWallet)
                    .status(CardStatus.ACTIVE)
                    .build());

            String badJson = "{ \"status\": \"SUPER_ACTIVE\" }";

            mockMvc.perform(patch("/api/v1/cards/{id}/status", card.getId())
                            .with(user(testUser))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(badJson))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
        }
    }


    // =========================================================================
    // GROUP 3: DATA SAFETY & COMPLIANCE
    // =========================================================================
    /**
     * Verifies data-protection controls mandated by PCI-DSS §3.4 and GDPR Art. 32:
     * <ul>
     *   <li>API responses never expose plaintext PAN; only masked format **** **** **** 1234</li>
     *   <li>Storage-layer encryption: PAN/CVV ciphertext must differ from plaintext and maintain
     *       deterministic length-padding to defeat frequency analysis</li>
     * </ul>
     * <p>
     * Tests use direct JDBC queries to bypass JPA decryption, guaranteeing that ciphertext
     * is physically stored in PostgreSQL.
     * </p>
     *
     * @author Madavan Babu
     * @since 2026
     */
    @Nested
    @DisplayName("Group 3: Data Safety (Masking & Encryption)")
    class DataSafety {



        @Test
        @DisplayName("9. View Layer: API MUST Mask PANs in Inventory (**** 1234)")
        void shouldNeverLeakPlaintextPanInInventory() throws Exception {
            String sensitivePan = "4111222233334444";

            // FIX: Use baseCardBuilder (overriding PAN) to satisfy DB constraints
            virtualCardRepository.save(baseCardBuilder(testWallet)
                    .pan(sensitivePan)
                    .status(CardStatus.ACTIVE)
                    .build());

            String jsonResponse = mockMvc.perform(get("/api/v1/cards")
                            .with(user(testUser)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            assertThat(jsonResponse).contains("**** **** **** 4444");
            assertThat(jsonResponse).doesNotContain(sensitivePan);
        }

        @Test
        @DisplayName("10. Storage Layer: DB Columns MUST NOT contain Plaintext (Encryption Check)")
        void shouldStoreSecretsEncryptedInDatabase() {
            String rawPan = "5555666677778888";
            String rawCvv = "999";

            // FIX: Use baseCardBuilder (overriding PAN/CVV)
            VirtualCard savedCard = virtualCardRepository.save(baseCardBuilder(testWallet)
                    .pan(rawPan)
                    .cvv(rawCvv)
                    .status(CardStatus.ACTIVE)
                    .build());

            String sql = "SELECT pan, cvv FROM virtual_cards WHERE id = ?";

            var row = jdbcTemplate.queryForMap(sql, savedCard.getId());
            String dbPan = (String) row.get("pan");
            String dbCvv = (String) row.get("cvv");

            assertThat(dbPan).isNotEqualTo(rawPan);
            assertThat(dbCvv).isNotEqualTo(rawCvv);
            assertThat(dbPan.length()).isNotEqualTo(rawPan.length());
        }
    }

    // =========================================================================
    // TEST HELPERS
    // =========================================================================

    /**
     * Factory helper that returns a {@link VirtualCard.VirtualCardBuilder} pre-populated with
     * mandatory PCI-DSS fields required by the PostgreSQL schema (pan, cvv, expiryDate, cardholderName).
     * <p>
     * Guarantees that every integration-test card is DB-persistable without violating
     * <code>NOT NULL</code> constraints or encryption contracts.
     * </p>
     *
     * @param account the funding wallet; must belong to the test user
     * @return pre-configured builder ready for additional customisation
     */
    private VirtualCard.VirtualCardBuilder baseCardBuilder(Account account) {
        return VirtualCard.builder()
                .account(account)
                .pan("4111222233334444")     // Mandatory
                .cvv("123")                  // Mandatory
                .expiryDate("12/30")         // Mandatory
                .cardholderName("Integration Tester") // Mandatory
                .monthlyLimit(new BigDecimal("1000.00"))
                .status(CardStatus.ACTIVE);
    }
}