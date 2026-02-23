package com.opayque.api.card.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.card.controller.CardController;
import com.opayque.api.card.dto.CardTransactionRequest;
import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.PaymentStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.encryption.AttributeEncryptor;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import com.opayque.api.wallet.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
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
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration test suite that validates the complete card‑transaction workflow
 * across the persistence, caching and service layers.
 * <p>
 * The purpose of this class is to ensure that the system behaves correctly for
 * a variety of realistic scenarios, ranging from a flawless debit to multiple
 * error conditions such as limit violations, insufficient wallet balance,
 * blind‑index mismatches, frozen card status, idempotency replays and rate‑limit
 * breaches. By exercising the full stack—including PostgreSQL, Redis,
 * Spring MVC, encryption utilities and domain services—the tests provide confidence
 * that the end‑to‑end processing logic satisfies both functional and non‑functional
 * requirements.
 * <p>
 * Key architectural components exercised by the tests:
 * <ul>
 *   <li>HTTP entry point: {@link CardController} – verifies response
 *       codes, payloads and idempotency handling.</li>
 *   <li>Business logic: {@link CardTransactionService} – drives validation,
 *       limit checking and status enforcement.</li>
 *   <li>Persistence: {@link LedgerRepository}, {@link UserRepository},
 *       {@link AccountRepository}, {@link VirtualCardRepository} – confirms
 *       ledger entries, account balances and card metadata are correctly stored.</li>
 *   <li>Caching: {@link RedisTemplate} – asserts
 *       that spend accumulators are created, updated or left untouched according
 *       to the scenario.</li>
 *   <li>Encryption: {@link AttributeEncryptor} – guarantees that sensitive fields
 *       (PAN, CVV, expiry) are stored encrypted and that decryption failures are
 *       handled securely.</li>
 * </ul>
 * <p>
 * Test fixtures (prefixed with <code>golden</code>) provide a fully populated user,
 * wallet and virtual card with known encrypted values. Raw card data such as
 * {@code RAW_CVV}, {@code RAW_EXPIRY} and {@code rawPan} are used to construct
 * request payloads. The {@code @DynamicPropertySource} method supplies containerised
 * PostgreSQL and Redis instances, ensuring reproducible isolated environments.
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see CardController
 * @see CardTransactionService
 * @see LedgerRepository
 * @see UserRepository
 * @see AccountRepository
 * @see VirtualCardRepository
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false) //Bypass Security Filters to allow "Public" Simulation traffic
@ActiveProfiles("test")
@Tag("integration")
@Testcontainers
class CardTransactionIntegrationTest {

    // =========================================================================
    // 1. INFRASTRUCTURE (The Real Deal)
    // =========================================================================
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.0-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Postgres: Force Hibernate Dialect for proper locking support
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none"); // Schema managed by migration/startup
        registry.add("spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation", () -> "true");

        // Redis: Connect to the dynamic Testcontainer port
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
    }

    // =========================================================================
    // 2. WIRING (Real Beans)
    // =========================================================================
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    // Repositories for Verification
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private VirtualCardRepository virtualCardRepository;
    @Autowired private LedgerRepository ledgerRepository;

    // Infrastructure Beans for Setup
    @Autowired private AttributeEncryptor attributeEncryptor;
    @Autowired private LedgerService ledgerService;

    // Redis for verification
    @Autowired private RedisTemplate<String, Object> redisTemplate;

    // Configuration Injection
    @Value("${opayque.card.bin:171103}")
    private String opayqueBin;

    // Test Data
    private User goldenUser;
    private Account goldenWallet;
    private VirtualCard goldenCard;

    private final String RAW_CVV = "123";
    private final String RAW_EXPIRY = "12/30";
    private String rawPan; // Initialized in setup() using BIN

    /**
     * Initializes the integration‑test “clean room” before each test case.
     *
     * <p>This method performs a deterministic setup of the persistence layer and in‑memory
     * data stores so that every {@code @Test} runs against an identical baseline.
     *
     * <p>The steps are:
     *
     * <ul>
     *   <li><b>Dynamic PAN creation</b> – Constructs a 16‑digit PAN using the configured
     *       {@code opayqueBin} and stores it in {@code rawPan} for later use.</li>
     *
     *   <li><b>Infrastructure flush</b> – Clears Redis via
     *       {@code redisTemplate.getRequiredConnectionFactory().getConnection()
     *        .serverCommands().flushAll()} and empties all JPA tables in the correct order
     *       (Ledger → VirtualCard → Account → User) to respect foreign‑key constraints.</li>
     *
     *   <li><b>Golden user provisioning</b> – Persists a privileged {@link User} record
     *       with role {@link Role#CUSTOMER} and keeps the reference in {@code goldenUser}.</li>
     *
     *   <li><b>Golden wallet provisioning</b> – Creates an {@link Account} for the golden
     *       user in EUR, with a PSD2‑compliant IBAN, and stores it in {@code goldenWallet}.</li>
     *
     *   <li><b>Initial funding</b> – Uses the real {@link LedgerService} to record a credit
     *       entry of €10 000 on the golden wallet, ensuring ledger consistency rather than
     *       inserting raw rows.</li>
     *
     *   <li><b>Golden virtual card issuance</b> – Persists a {@link VirtualCard} linked to the
     *       golden wallet. Because the service layer is bypassed, the sensitive fields
     *       {@code cvv} and {@code expiryDate} are manually encrypted via
     *       {@link AttributeEncryptor#convertToDatabaseColumn(String)} (Object)}.</li>
     * </ul>
     *
     * <p>After execution the test suite has a fully functional user, account, ledger entry
     * and active virtual card ready for the transaction scenarios defined in this class.
     *
     * @see CardController
     * @see CardTransactionService
     * @see VirtualCardRepository
     */
    @BeforeEach
    void setup() {
        // 0. Initialize Dynamic Data
        // Construct a valid 16-digit PAN using the 171103 BIN
        this.rawPan = opayqueBin + "0000001234";

        // 1. FLUSH INFRASTRUCTURE (The "Clean Room")
        redisTemplate.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        ledgerRepository.deleteAll(); // Delete Ledger first (FKs)
        virtualCardRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        // 2. PROVISION GOLDEN USER
        goldenUser = userRepository.saveAndFlush(User.builder()
                .email("vip@opayque.com")
                .fullName("VIP User")
                .password("hashed_secret")
                .role(Role.CUSTOMER)
                .build());

        // 3. PROVISION GOLDEN WALLET (EUR - PSD2 Compliant)
        goldenWallet = accountRepository.saveAndFlush(Account.builder()
                .user(goldenUser)
                .currencyCode("EUR") // Strict Compliance: No USD
                .iban("EU99VIP0000001")
                .build());

        // 4. FUND THE WALLET (The "Deposit")
        // We use the Real LedgerService to ensure balance consistency
        ledgerService.recordEntry(new CreateLedgerEntryRequest(
                goldenWallet.getId(),
                new BigDecimal("10000.00"), // Start with €10k
                "EUR",
                TransactionType.CREDIT,
                "Initial Funding",
                LocalDateTime.now(),
                UUID.randomUUID()
        ));

        // 5. ISSUE GOLDEN CARD (Active, €5k Limit)
        // CRITICAL: Pass plaintext! Hibernate's @Converter will encrypt it automatically.
        goldenCard = virtualCardRepository.saveAndFlush(VirtualCard.builder()
                .account(goldenWallet)
                .pan(rawPan)
                .cvv(RAW_CVV) // Removed attributeEncryptor
                .expiryDate(RAW_EXPIRY) // Removed attributeEncryptor
                .cardholderName("VIP User")
                .status(CardStatus.ACTIVE)
                .monthlyLimit(new BigDecimal("5000.00"))
                .build());
    }

    // =========================================================================
    // CATEGORY 1: THE "GOLDEN SWIPE" (Functional Success)
    // =========================================================================

    /**
     * <b>Scenario 1.1: The Perfect Debit</b>
     * <p>
     * A completely valid transaction under limits and with sufficient funds.
     * <p>
     * <b>Deep Verification:</b>
     * 1. Controller: 200 OK, Approved.
     * 2. Redis: 'spend' key exists and equals amount.
     * 3. Ledger: Debit entry created.
     * 4. Idempotency: Key is marked complete.
     */
    @Test
    @DisplayName("1.1 Golden Swipe: Valid EUR Transaction -> Ledger Debit & Redis Accumulator")
    void shouldProcessPerfectDebit() throws Exception {
        BigDecimal txnAmount = new BigDecimal("150.00");
        String externalTxnId = UUID.randomUUID().toString();

        // [FIXED ORDER]: PAN, CVV, Expiry, Amount, Currency, Merchant, MCC, ExternalID
        CardTransactionRequest request = new CardTransactionRequest(
                rawPan,
                RAW_CVV,
                RAW_EXPIRY,
                txnAmount,
                "EUR",
                "Starbucks Paris",
                "5411",
                externalTxnId
        );

        // 1. EXECUTE
        mockMvc.perform(post("/api/v1/simulation/card-transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(PaymentStatus.APPROVED.name()))
                .andExpect(jsonPath("$.approvalCode").exists());

        // 2. VERIFY REDIS (Accumulator)
        // Key Pattern: spend:{cardId}:{yyyy-MM}
        String month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        String redisKey = "spend:" + goldenCard.getId() + ":" + month;

        Double currentSpend = redisTemplate.opsForValue().get(redisKey) != null
                ? Double.valueOf(redisTemplate.opsForValue().get(redisKey).toString())
                : 0.0;

        assertThat(currentSpend).isEqualTo(150.00);

        // 3. VERIFY LEDGER (The "Money Move")
        long ledgerCount = ledgerRepository.count();
        // 1 Credit (Funding) + 1 Debit (Swipe) = 2
        assertThat(ledgerCount).isEqualTo(2);

        // Verify the latest entry
        // [FIXED METHOD]: Using getTransactionType() to match your Entity
        var debitEntry = ledgerRepository.findAll().stream()
                .filter(l -> l.getTransactionType() == TransactionType.DEBIT)
                .findFirst()
                .orElseThrow();

        assertThat(debitEntry.getAmount()).isEqualByComparingTo(txnAmount);
        assertThat(debitEntry.getAccount().getId()).isEqualTo(goldenWallet.getId());
        assertThat(debitEntry.getCurrency()).isEqualTo("EUR");
    }

    // =========================================================================
    // CATEGORY 2: THE "FINANCIAL FIREWALLS" (Limits & Funds)
    // =========================================================================

    /**
     * <b>Scenario 2.1: The Monthly Ceiling</b>
     * <p>
     * Attempting to spend more than the card's defined monthly limit.
     * Limit: €5000. Current: €0. Request: €5001.
     * <p>
     * <b>Deep Verification:</b>
     * 1. Controller: 409 Conflict (or Service specific error code).
     * 2. Redis: Accumulator stays at 0.
     * 3. Ledger: No new entries (Count stays at 1).
     */
    @Test
    @DisplayName("2.1 Limit Breach: Request exceeding Monthly Limit -> Blocked")
    void shouldBlockLimitBreach() throws Exception {
        BigDecimal breachAmount = new BigDecimal("5001.00"); // €1 over limit
        String externalTxnId = UUID.randomUUID().toString();

        // [FIXED ORDER]: PAN, CVV, Expiry, Amount, Currency, Merchant, MCC, ExternalID
        CardTransactionRequest request = new CardTransactionRequest(
                rawPan,
                RAW_CVV,
                RAW_EXPIRY,
                breachAmount,
                "EUR",
                "Apple Store Berlin",
                "5732",
                externalTxnId
        );

        // 1. EXECUTE (Expect 4xx Error)
        mockMvc.perform(post("/api/v1/simulation/card-transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError());

        // 2. VERIFY REDIS (Must not increment)
        String month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        String redisKey = "spend:" + goldenCard.getId() + ":" + month;
        Object spendVal = redisTemplate.opsForValue().get(redisKey);
        assertThat(spendVal).isNull(); // Should be null or 0

        // 3. VERIFY LEDGER (Atomicity)
        // Should only have the initial Funding Credit (1)
        assertThat(ledgerRepository.count()).isEqualTo(1);
    }

    /**
     * <b>Scenario 2.2: The Empty Wallet</b>
     * <p>
     * Valid card, under limit, but the underlying Wallet is broke.
     * Wallet: €0. Request: €10.
     * <p>
     * <b>Deep Verification:</b>
     * 1. Controller: 402 Payment Required (or 400 Bad Request).
     * 2. Ledger: No new entries.
     */
    @Test
    @DisplayName("2.2 Insufficient Funds: Empty GBP Wallet -> Blocked")
    void shouldBlockInsufficientFunds() throws Exception {
        // A. Setup: Create a poor user
        User poorUser = userRepository.saveAndFlush(User.builder().email("broke@opayque.com").fullName("Broke User").password("pw").role(Role.CUSTOMER).build());
        Account poorWallet = accountRepository.saveAndFlush(Account.builder().user(poorUser).currencyCode("GBP").iban("GB99BROKE").build());
        String externalTxnId = UUID.randomUUID().toString();

        // Ensure Balance is ZERO (No funding ledger entry)

        // Issue Card for poor user using correct BIN
        String poorPan = opayqueBin + "999999999999";

        virtualCardRepository.saveAndFlush(VirtualCard.builder()
                .account(poorWallet)
                .pan(poorPan)
                .cvv("123") // Removed attributeEncryptor
                .expiryDate("12/30") // Removed attributeEncryptor
                .status(CardStatus.ACTIVE)
                .monthlyLimit(new BigDecimal("1000.00"))
                .cardholderName("Broke User")
                .build());

        // B. Execute
        // [FIXED ORDER]: PAN, CVV, Expiry, Amount, Currency, Merchant, MCC, ExternalID
        CardTransactionRequest request = new CardTransactionRequest(
                poorPan,
                "123",
                "12/30",
                new BigDecimal("10.00"),
                "GBP",
                "Tesco London",
                "5411",
                externalTxnId
        );

        mockMvc.perform(post("/api/v1/simulation/card-transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError()); // Typically 402 or 400

        // C. Verify Ledger

        // Ledger should have ZERO entries for this account
        long debitCount = ledgerRepository.findAll().stream()
                .filter(l -> l.getAccount().getId().equals(poorWallet.getId()))
                .count();
        assertThat(debitCount).isZero();
    }

    // =========================================================================
    // CATEGORY 3: THE "SECURITY FORTRESS" (Auth & Fraud)
    // =========================================================================

    /**
     * <b>Scenario 3.1: The "Ghost Card" (Blind Index Defense)</b>
     * <p>
     * A request with a PAN that passes Luhn checks (valid format) but does not exist in the DB.
     * <p>
     * <b>Deep Verification:</b>
     * 1. Controller: Returns 401 Unauthorized (via BadCredentialsException).
     * 2. Security: The Service fails fast at the "Blind Index Lookup" stage.
     * 3. Ledger: Zero side effects.
     */
    @Test
    @DisplayName("3.1 Ghost Card: Non-existent PAN -> 401 Unauthorized")
    void shouldBlockGhostCard() throws Exception {
        // Construct a PAN that looks valid (starts with correct BIN) but wasn't issued
        String ghostPan = opayqueBin + "9999999999";
        String externalTxnId = UUID.randomUUID().toString();

        CardTransactionRequest request = new CardTransactionRequest(
                ghostPan,
                RAW_CVV,
                RAW_EXPIRY,
                new BigDecimal("50.00"),
                "EUR",
                "Dark Web Merchant",
                "5999",
                externalTxnId
        );

        // 1. EXECUTE & VERIFY
        // BadCredentialsException typically maps to 401
        mockMvc.perform(post("/api/v1/simulation/card-transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());

        // 2. LEDGER CHECK
        // Ensure no phantom debits occurred
        // Expect 1 (Funding only), because this test runs isolated/fresh.
        assertThat(ledgerRepository.count()).isEqualTo(1);
    }

    /**
     * <b>Scenario 3.2: The "Criminal Swipe" (Crypto Mismatch)</b>
     * <p>
     * Valid PAN, but the CVV (or Expiry) does not match the encrypted values in the DB.
     * <p>
     * <b>Deep Verification:</b>
     * 1. Controller: Returns 401 Unauthorized (via BadCredentialsException).
     * 2. Logic: The Service decrypts the real CVV, compares, and rejects the mismatch.
     */
    @Test
    @DisplayName("3.2 Criminal Swipe: Invalid CVV -> 401 Unauthorized")
    void shouldBlockCryptoMismatch() throws Exception {
        String externalTxnId = UUID.randomUUID().toString();

        CardTransactionRequest request = new CardTransactionRequest(
                rawPan, // Valid Golden PAN
                "999",  // WRONG CVV
                RAW_EXPIRY,
                new BigDecimal("50.00"),
                "EUR",
                "Suspect Merchant",
                "5999",
                externalTxnId
        );

        // 1. EXECUTE & VERIFY
        mockMvc.perform(post("/api/v1/simulation/card-transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * <b>Scenario 3.3: The "Frozen Asset" (Status Block)</b>
     * <p>
     * Card exists, credentials match, but Status is FROZEN.
     * <p>
     * <b>Deep Verification:</b>
     * 1. Controller: Returns 403 Forbidden (via AccessDeniedException).
     * 2. Logic: Service explicitly checks card.getStatus().
     */
    @Test
    @DisplayName("3.3 Frozen Asset: Valid Credentials but FROZEN Status -> 403 Forbidden")
    void shouldBlockFrozenCard() throws Exception {
        // A. SETUP: Create a Frozen Card
        String frozenPan = opayqueBin + "8888888888";

        virtualCardRepository.saveAndFlush(VirtualCard.builder()
                .account(goldenWallet) // Same wallet is fine
                .pan(frozenPan)
                .cvv(RAW_CVV) // Removed attributeEncryptor
                .expiryDate(RAW_EXPIRY) // Removed attributeEncryptor
                .cardholderName("VIP User")
                .status(CardStatus.FROZEN) // <--- THE BLOCKER
                .monthlyLimit(new BigDecimal("5000.00"))
                .build());

        String externalTxnId = UUID.randomUUID().toString();

        CardTransactionRequest request = new CardTransactionRequest(
                frozenPan,
                RAW_CVV,
                RAW_EXPIRY,
                new BigDecimal("50.00"),
                "EUR",
                "Netflix Subscription",
                "5815",
                externalTxnId
        );

        // B. EXECUTE & VERIFY
        // AccessDeniedException maps to 403 Forbidden
        mockMvc.perform(post("/api/v1/simulation/card-transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // CATEGORY 4: THE "RESILIENCE LAYER" (Idempotency & Velocity)
    // =========================================================================

    /**
     * <b>Scenario 4.1: The "Double Tap" (Idempotency Replay)</b>
     * <p>
     * Sending the exact same payload with the same externalTransactionId twice.
     * <p>
     * <b>Deep Verification:</b>
     * 1. Call 1: 200 OK (Approved).
     * 2. Call 2: 409 Conflict (Idempotency Exception).
     * 3. Ledger: Only ONE debit row exists in the DB for this ID.
     */
    @Test
    @DisplayName("4.1 Double Tap: Replay Attack -> 409 Conflict & Single Ledger Entry")
    void shouldBlockReplayAttack() throws Exception {
        String fixedExternalId = UUID.randomUUID().toString();
        BigDecimal amount = new BigDecimal("20.00");

        CardTransactionRequest request = new CardTransactionRequest(
                rawPan,
                RAW_CVV,
                RAW_EXPIRY,
                amount,
                "EUR",
                "Uber Rides",
                "4121",
                fixedExternalId // SHARED ID
        );

        String jsonPayload = objectMapper.writeValueAsString(request);

        // 1. FIRST TAP (Success)
        mockMvc.perform(post("/api/v1/simulation/card-transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(PaymentStatus.APPROVED.name()));

        // 2. SECOND TAP (Replay)
        // Expect 409 Conflict (mapped from IdempotencyException)
        mockMvc.perform(post("/api/v1/simulation/card-transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload))
                .andExpect(status().isConflict());

        // 3. LEDGER VERIFICATION
        // Filter by the exact amount and account to ensure we don't count funding
        long debits = ledgerRepository.findAll().stream()
                .filter(l -> l.getAccount().getId().equals(goldenWallet.getId()))
                .filter(l -> l.getTransactionType() == TransactionType.DEBIT)
                .filter(l -> l.getAmount().compareTo(amount) == 0)
                .count();

        assertThat(debits).as("Double debit detected! Idempotency failed.").isEqualTo(1);
    }

    /**
     * <b>Scenario 4.2: The "Velocity Trap" (Rate Limiting)</b>
     * <p>
     * Firing 21 transactions in rapid succession against a Quota of 20/min.
     * <p>
     * <b>Deep Verification:</b>
     * 1. Calls 1-20: 200 OK.
     * 2. Call 21: 429 Too Many Requests (or 409/422 based on ExceptionHandler).
     * 3. Ledger: Exactly 20 debits recorded.
     */
    @Test
    @DisplayName("4.2 Velocity Trap: Exceeding 20 Txn/Min -> Blocked")
    void shouldEnforceVelocityLimit() throws Exception {
        // We use a fresh card/user to ensure bucket is strictly empty (though setup() flushes Redis, this is safer)
        // Actually, setup() flushes Redis, so Golden Card is safe to use.

        int quota = 20;
        BigDecimal amount = new BigDecimal("1.00"); // Small amounts to avoid Limit Breach

        // 1. FILL THE BUCKET (20 Requests)
        for (int i = 0; i < quota; i++) {
            CardTransactionRequest request = new CardTransactionRequest(
                    rawPan,
                    RAW_CVV,
                    RAW_EXPIRY,
                    amount,
                    "EUR",
                    "Rapid Fire Merchant",
                    "5411",
                    UUID.randomUUID().toString() // Unique IDs for each
            );

            mockMvc.perform(post("/api/v1/simulation/card-transaction")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk());
        }

        // 2. THE BREAKING STRAW (21st Request)
        CardTransactionRequest breachRequest = new CardTransactionRequest(
                rawPan,
                RAW_CVV,
                RAW_EXPIRY,
                amount,
                "EUR",
                "Blocked Merchant",
                "5411",
                UUID.randomUUID().toString()
        );

        // Expect 429 Too Many Requests
        // Note: If your GlobalExceptionHandler maps RateLimitExceededException to 429, use isTooManyRequests()
        // If it maps to 409/400, adjust accordingly. Standard is 429.
        mockMvc.perform(post("/api/v1/simulation/card-transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(breachRequest)))
                .andExpect(status().isTooManyRequests());

        // 3. LEDGER VERIFICATION
        // Should have exactly 20 DEBITS
        long debitCount = ledgerRepository.findAll().stream()
                .filter(l -> l.getAccount().getId().equals(goldenWallet.getId()))
                .filter(l -> l.getTransactionType() == TransactionType.DEBIT)
                .filter(l -> l.getDescription().contains("Rapid Fire")) // Filter only this test's transactions
                .count();

        assertThat(debitCount).as("Velocity limit breached! Ledger contains too many entries.").isEqualTo(20);
    }

    // =========================================================================
    // EPIC 5: ACCOUNT STATUS GUARDRAIL TESTS (INTEGRATION)
    // =========================================================================

    @Test
    @DisplayName("Integration: Should REJECT transaction if Account is FROZEN (Kill-Switch)")
    void shouldRejectTransaction_WhenAccountIsFrozen() throws Exception {
        // 1. Simulate Admin Action: Freeze the wallet
        goldenWallet.setStatus(AccountStatus.FROZEN);
        accountRepository.save(goldenWallet);

        // 2. Capture Baseline (Likely 1 due to seed funds)
        long initialLedgerCount = ledgerRepository.count();

        // 3. Construct the Payload
        CardTransactionRequest request = new CardTransactionRequest(
                rawPan,
                RAW_CVV,
                RAW_EXPIRY,
                new BigDecimal("50.00"),
                "EUR",
                "Suspicious Merchant",
                "5999",
                UUID.randomUUID().toString()
        );

        // 4. Execute & Verify HTTP Response
        mockMvc.perform(post("/api/v1/simulation/card-transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden()) // 403
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access Denied: You do not have permission to view this resource"));

        // 5. Critical: Verify Ledger Integrity
        // The count must be EXACTLY the same as before. No new debit added.
        assertThat(ledgerRepository.count())
                .as("Ledger count should remain unchanged (Money did not leave)")
                .isEqualTo(initialLedgerCount);
    }

    @Test
    @DisplayName("Integration: Should REJECT transaction if Account is CLOSED (Zombie Charge)")
    void shouldRejectTransaction_WhenAccountIsClosed() throws Exception {
        // 1. Simulate User Action: Close the wallet
        goldenWallet.setStatus(AccountStatus.CLOSED);
        accountRepository.save(goldenWallet);

        // 2. Capture Baseline
        long initialLedgerCount = ledgerRepository.count();

        // 3. Construct the Payload
        CardTransactionRequest request = new CardTransactionRequest(
                rawPan,
                RAW_CVV,
                RAW_EXPIRY,
                new BigDecimal("12.99"),
                "EUR",
                "Netflix Subscription",
                "4899",
                UUID.randomUUID().toString()
        );

        // 4. Execute & Verify HTTP Response
        mockMvc.perform(post("/api/v1/simulation/card-transaction")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("Access Denied: You do not have permission to view this resource"));

        // 5. Verify Ledger Integrity
        assertThat(ledgerRepository.count())
                .as("Ledger count should remain unchanged")
                .isEqualTo(initialLedgerCount);
    }
}