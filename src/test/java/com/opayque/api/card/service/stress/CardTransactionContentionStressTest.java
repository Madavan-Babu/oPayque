package com.opayque.api.card.service.stress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opayque.api.card.dto.CardTransactionRequest;
import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.infrastructure.encryption.AttributeEncryptor;
import com.opayque.api.infrastructure.ratelimit.RateLimiterService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import com.opayque.api.wallet.service.LedgerService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The "Contention" Vector (Locking & Atomicity).
 * <p>
 * Targets Single Resource with massive concurrency to force Race Conditions.
 * Validates PESSIMISTIC_WRITE locks, Redis Atomicity, and Idempotency Guards.
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false) // Bypass Auth for Pure Stress Testing
@ActiveProfiles("test")
@Tag("stress")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // CRITICAL: Prevents H2 override
class CardTransactionContentionStressTest {

    // =========================================================================
    // 1. INFRASTRUCTURE
    // =========================================================================
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.0-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // A. Primary Datasource
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);

        // B. Liquibase (Explicitly point to the container)
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);
        registry.add("spring.liquibase.driver-class-name", postgres::getDriverClassName);

        // C. Hibernate Configuration
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // This stays 'validate' to ensure the test fails if migration is broken
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

        // D. Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    // =========================================================================
    // 2. WIRING
    // =========================================================================
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private VirtualCardRepository virtualCardRepository;
    @Autowired private LedgerRepository ledgerRepository;
    @Autowired private LedgerService ledgerService;
    @Autowired private AttributeEncryptor attributeEncryptor;
    @Autowired private RedisTemplate<String, Object> redisTemplate;

    // SPY: Disable Rate Limiting for Locking Tests to force DB Contention
    @MockitoSpyBean private RateLimiterService rateLimiterService;

    @Value("${opayque.card.bin:171103}")
    private String opayqueBin;

    private User stressUser;
    private Account stressWallet;
    private VirtualCard stressCard;
    private String rawPan;
    private final String RAW_CVV = "123";
    private final String RAW_EXPIRY = "12/30";

    @BeforeEach
    void setup() {
        // 1. FLUSH INFRASTRUCTURE
        redisTemplate.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        ledgerRepository.deleteAll();
        virtualCardRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        // 2. SETUP DATA
        this.rawPan = opayqueBin + "9999999999";

        stressUser = userRepository.saveAndFlush(User.builder()
                .email("stress_contention@opayque.com")
                .fullName("Contention Tester")
                .password("hashed")
                .role(Role.CUSTOMER)
                .build());

        stressWallet = accountRepository.saveAndFlush(Account.builder()
                .user(stressUser)
                .currencyCode("EUR")
                .iban("EU99STRESS001")
                .build());

        // Fund Wallet with Infinite Money (to isolate Limit logic)
        ledgerService.recordEntry(new CreateLedgerEntryRequest(
                stressWallet.getId(), new BigDecimal("1000000.00"), "EUR",
                TransactionType.CREDIT, "Infinite Funding", LocalDateTime.now(), UUID.randomUUID()
        ));

        // Create Card (Active)
        stressCard = virtualCardRepository.saveAndFlush(VirtualCard.builder()
                .account(stressWallet)
                .pan(rawPan)
                .cvv(attributeEncryptor.convertToDatabaseColumn(RAW_CVV))
                .expiryDate(attributeEncryptor.convertToDatabaseColumn(RAW_EXPIRY))
                .cardholderName("Stress User")
                .status(CardStatus.ACTIVE)
                .monthlyLimit(new BigDecimal("50000.00")) // Default High Limit
                .build());
    }

    // =========================================================================
    // TEST 1: The "Black Friday" Monolith (Hot-Spot Contention)
    // =========================================================================
    /**
     * <b>Target:</b> 1 Card, 1000 Concurrent Transactions.
     * <p>
     * <b>The Attack:</b> Fire 1000 valid requests for $1.00 each on the same card in parallel.
     * <b>The Goal:</b> Break the Database Row Lock or the Redis Accumulator.
     * <p>
     * <b>Bank-Grade Check:</b>
     * <ul>
     * <li>DB: Ledger must have exactly 1000 DEBIT entries.</li>
     * <li>Redis: spend:{id} must match exactly 1000.00.</li>
     * <li>Consistency: Redis and Ledger must match perfectly.</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 1: Black Friday - 1000 Threads vs 1 Card Row Lock")
    void blackFriday_HotSpotContention() throws InterruptedException {
        // 1. Disable Rate Limiting (Focus on DB Locking)
        doNothing().when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());

        int threadCount = 1000;
        BigDecimal amount = new BigDecimal("1.00");
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        log.info("STARTING: Black Friday Monolith ({} Threads)...", threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    String externalId = UUID.randomUUID().toString();
                    CardTransactionRequest request = new CardTransactionRequest(
                            rawPan, RAW_CVV, RAW_EXPIRY, amount, "EUR",
                            "Amazon Black Friday", "5411", externalId
                    );

                    latch.await(); // Wait for Gun

                    MvcResult result = mockMvc.perform(post("/api/v1/simulation/card-transaction")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                            .andReturn();

                    if (result.getResponse().getStatus() == 200) {
                        successCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                        log.error("Txn Failed: {}", result.getResponse().getStatus());
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    log.error("Exception", e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // FIRE
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        log.info("FINISHED. Success: [{}], Errors: [{}]", successCount.get(), errorCount.get());

        // ASSERTIONS
        assertThat(finished).as("Test Timed Out").isTrue();
        assertThat(errorCount.get()).as("Unexpected Failures (Lock Timeout?)").isZero();
        assertThat(successCount.get()).isEqualTo(threadCount);

        // 1. LEDGER CHECK
        long debitCount = ledgerRepository.findAll().stream()
                .filter(l -> l.getTransactionType() == TransactionType.DEBIT)
                .count();
        assertThat(debitCount).as("Ledger Rows missing! Ghost Spends detected.").isEqualTo(threadCount);

        // 2. REDIS CHECK
        String month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        String redisKey = "spend:" + stressCard.getId() + ":" + month;
        Double redisSpend = Double.valueOf(redisTemplate.opsForValue().get(redisKey).toString());

        assertThat(redisSpend).as("Redis Accumulator Desync!").isEqualTo(1000.00);
    }

    // =========================================================================
    // TEST 2: The "Photo Finish" (Limit Boundary Race)
    // =========================================================================
    /**
     * <b>Target:</b> 1 Card, Limit $100.00, Current Spend $0.00.
     * <p>
     * <b>The Attack:</b> Fire 50 Threads attempting to spend $2.01 each.
     * Mathematical Total: $100.50 (Over Limit).
     * <p>
     * <b>The Goal:</b> Trick the system into allowing the 50th transaction via "Check-Then-Act" race.
     * <p>
     * <b>Bank-Grade Check:</b>
     * <ul>
     * <li>Success: Exactly 49 transactions pass ($98.49).</li>
     * <li>Fail: Exactly 1 transaction fails (The one that pushes it to $100.50).</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 2: Photo Finish - Limit Race Condition")
    void photoFinish_LimitBoundaryRace() throws Exception {
        // 1. Disable Rate Limiting (Focus on Limit Logic Race)
        doNothing().when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());

        // 2. Update Card Limit to $100.00
        stressCard.setMonthlyLimit(new BigDecimal("100.00"));
        virtualCardRepository.saveAndFlush(stressCard);

        int threadCount = 50;
        BigDecimal amount = new BigDecimal("2.01");
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger approvedCount = new AtomicInteger(0);
        AtomicInteger rejectedCount = new AtomicInteger(0);

        log.info("STARTING: Photo Finish (Target: $100.00 Limit, Attack: 50x $2.01)...");

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    CardTransactionRequest request = new CardTransactionRequest(
                            rawPan, RAW_CVV, RAW_EXPIRY, amount, "EUR",
                            "Race Merchant", "5411", UUID.randomUUID().toString()
                    );

                    MvcResult result = mockMvc.perform(post("/api/v1/simulation/card-transaction")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                            .andReturn();

                    int status = result.getResponse().getStatus();
                    if (status == 200) {
                        approvedCount.incrementAndGet();
                    } else if (status >= 400) {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    log.error("Ex", e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        log.info("FINISHED. Approved: [{}], Rejected: [{}]", approvedCount.get(), rejectedCount.get());

        // ASSERTIONS
        // Total Potential: $100.50. Limit: $100.00.
        // Allowed: 49 * 2.01 = 98.49.
        // Blocked: 1 * 2.01 (would make it 100.50).
        assertThat(approvedCount.get())
                .as("Limit Breach! Race condition allowed too many txns.")
                .isEqualTo(49);

        assertThat(rejectedCount.get())
                .as("Expected exactly 1 rejection at the boundary.")
                .isEqualTo(1);

        // Verify Redis Exact State
        String month = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
        String redisKey = "spend:" + stressCard.getId() + ":" + month;
        Double redisSpend = Double.valueOf(redisTemplate.opsForValue().get(redisKey).toString());

        assertThat(redisSpend).as("Redis Accumulator incorrect").isEqualTo(98.49);
    }

    // =========================================================================
    // TEST 3: The "Stuttering Finger" (Idempotency Storm)
    // =========================================================================
    /**
     * <b>Target:</b> 1 Valid Request Payload, 500 Concurrent Replays.
     * <p>
     * <b>The Attack:</b> Blast the exact same externalTransactionId 500 times.
     * <b>The Goal:</b> Force a "Double Debit" or a "Race to Commit".
     * <p>
     * <b>Bank-Grade Check:</b>
     * <ul>
     * <li>Result: Exactly 1 HTTP 200 OK. Exactly 499 HTTP 409 Conflict.</li>
     * <li>Ledger: Exactly 1 Row.</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 3: Stuttering Finger - Idempotency Lock Storm")
    void stutteringFinger_IdempotencyStorm() throws InterruptedException {
        // Rate limiting doesn't matter here (Idempotency check is first), but good to disable for noise
        doNothing().when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());

        int threadCount = 500;
        String sharedExternalId = UUID.randomUUID().toString(); // THE SHARED KEY
        BigDecimal amount = new BigDecimal("10.00");

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        log.info("STARTING: Stuttering Finger (500 Replays)...");

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    CardTransactionRequest request = new CardTransactionRequest(
                            rawPan, RAW_CVV, RAW_EXPIRY, amount, "EUR",
                            "Replay Merchant", "5411", sharedExternalId
                    );

                    MvcResult result = mockMvc.perform(post("/api/v1/simulation/card-transaction")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                            .andReturn();

                    int status = result.getResponse().getStatus();
                    if (status == 200) successCount.incrementAndGet();
                    else if (status == 409) conflictCount.incrementAndGet();
                    else otherErrors.incrementAndGet();

                } catch (Exception e) {
                    otherErrors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        log.info("FINISHED. Success: [{}], Conflicts: [{}], Other: [{}]",
                successCount.get(), conflictCount.get(), otherErrors.get());

        // ASSERTIONS
        assertThat(successCount.get())
                .as("Double Spend Detected! More than 1 txn succeeded.").isEqualTo(1);

        assertThat(conflictCount.get())
                .as("Idempotency Guard failed").isEqualTo(499);

        // Verify Ledger
        long debitCount = ledgerRepository.findAll().stream()
                .filter(l -> l.getTransactionType() == TransactionType.DEBIT)
                .count();
        assertThat(debitCount).as("Ledger has duplicate entries").isEqualTo(1);
    }
}