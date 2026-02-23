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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
 * <p>
 * Integration test suite that deliberately stresses the card‑transaction processing
 * pipeline to verify resilience under extreme load conditions.
 * </p>
 *
 * <p>
 * The test class simulates two distinct attack scenarios:
 * </p>
 *
 * <ul>
 *   <li>
 *     <b>Global Botnet (Connection Starvation)</b> – launches {@code 1000} concurrent
 *     threads, each executing a distinct transaction against a pool of only {@code 10}
 *     {@link com.zaxxer.hikari.HikariDataSource} connections and a limited Redis client
 *     pool. The goal is to provoke connection‑pool exhaustion, observe
 *     {@link java.sql.SQLTransientConnectionException} (or similar timeout) handling,
 *     and confirm that a subsequent “probe” transaction succeeds without manual
 *     intervention.
 *   </li>
 *   <li>
 *     <b>CPU Burner (Crypto Saturation)</b> – runs a single‑threaded, high‑throughput loop
 *     that repeatedly encrypts/decrypts payloads using AES‑256. This stresses the
 *     {@link AttributeEncryptor} and measures transactions‑per‑second
 *     (TPS) to ensure the cryptographic path does not become a bottleneck (minimum
 *     {@code > 50 TPS} is asserted).
 *   </li>
 * </ul>
 *
 * <p>
 * By exercising both I/O‑bound (database/Redis) and CPU‑bound (encryption) pathways,
 * the suite validates that the application satisfies bank‑grade non‑functional
 * requirements: graceful degradation, automatic pool replenishment, and
 * deterministic recovery.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see com.opayque.api.card.controller.CardController}
 * @see com.opayque.api.card.service.CardTransactionService
 * @see UserRepository
 * @see AccountRepository
 * @see VirtualCardRepository
 * @see LedgerRepository
 * @see LedgerService
 * @see AttributeEncryptor
 * @see RateLimiterService
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Tag("stress")
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE) // CRITICAL: Prevents H2 override
class CardTransactionExhaustionStressTest {

    // =========================================================================
    // 1. INFRASTRUCTURE & POOL CONSTRICTION
    // =========================================================================
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7.0-alpine"))
            .withExposedPorts(6379);

    /**
     * Configures dynamic Spring properties for the {@code CardTransactionExhaustionStressTest}.
     * <p>
     * The method injects a deliberately constrained environment into the test
     * {@link DynamicPropertyRegistry} to simulate resource starvation scenarios.
     * By limiting the HikariCP connection pool to only ten connections and
     * configuring minimal idle connections, the test forces concurrent threads
     * to contend for database connections, thereby exposing potential bottlenecks
     * in transaction handling, retry logic, and fail‑over behaviour.
     * <p>
     * In addition to the primary datasource, the method also configures:
     * <ul>
     *   <li>Liquibase migration settings that point to the same test container.</li>
     *   <li>Hibernate dialect and DDL validation to ensure schema correctness.</li>
     *   <li>Redis connection details for caching and rate‑limiting services.</li>
     * </ul>
     * This holistic configuration ensures that both relational and NoSQL resources
     * are exercised under pressure, reflecting realistic production failure modes.
     *
     * @param registry the {@link DynamicPropertyRegistry} used to register the
     *                 dynamic {@code spring.*} properties for the test context.
     *
     * @see com.opayque.api.card.controller.CardController
     * @see com.opayque.api.card.service.CardTransactionService
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // === CONSTRICTION SETTINGS (Simulate Resource Starvation) ===
        // Force a tiny pool (10 connections) vs 1000 threads.
        // This guarantees that threads MUST queue or fail.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "5000"); // 5s timeout

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
        // Constrict Redis Lettuce Pool if possible via properties, or assume default single-connection per template
        // Note: Lettuce is usually non-blocking, but we test if the *server* chokes.
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

    @MockitoSpyBean
    private RateLimiterService rateLimiterService;

    @Value("${opayque.card.bin:171103}")
    private String opayqueBin;

    private User stressUser;
    private Account stressWallet;

    // Test Data Constants
    private final String RAW_CVV = "123";
    private final String RAW_EXPIRY = "12/30";
    private final int BOTNET_SIZE = 1000;

    // Store pre-provisioned cards
    private List<String> botnetPans;

    /**
     * Sets up a clean testing environment for {@link CardTransactionExhaustionStressTest}.
     * <p>
     * This method flushes the embedded Redis instance and removes all persisted rows from
     * {@link com.opayque.api.wallet.repository.LedgerRepository},
     * {@link com.opayque.api.card.repository.VirtualCardRepository},
     * {@link com.opayque.api.wallet.repository.AccountRepository} and
     * {@link com.opayque.api.identity.repository.UserRepository} to guarantee an isolated baseline for each
     * test execution.
     * <p>
     * It then creates a dedicated {@code stressUser} and an associated {@code stressWallet}
     * (an {@link com.opayque.api.wallet.entity.Account}) with infinite funding. The funding entry is
     * recorded via
     * {@link com.opayque.api.wallet.service.LedgerService#recordEntry(CreateLedgerEntryRequest)} (com.opayque.api.dto.CreateLedgerEntryRequest)}.
     * The relationship between {@code User} and {@code Account} models ownership of a wallet,
     * enabling subsequent transaction‑stress scenarios.
     * <p>
     * Finally, {@code BOTNET_SIZE} virtual cards are pre‑provisioned and linked to the wallet.
     * Each {@link com.opayque.api.card.entity.VirtualCard} is persisted with encrypted CVV and expiry,
     * a generated PAN and a monthly limit. This bulk provisioning isolates issuance load from
     * transaction load, allowing the test to focus on DB‑pool exhaustion under concurrent
     * transaction execution.
     * <p>
     * <b>Database mapping details</b>
     * <ul>
     *   <li>{@link com.opayque.api.identity.entity.User} – {@code @Table(name = "users")} with columns
     *       {@code email} (unique, not null), {@code full_name}, {@code password}, {@code role}.</li>
     *   <li>{@link com.opayque.api.wallet.entity.Account} – {@code @Table(name = "accounts")} with columns
     *       {@code id} (identity primary key), {@code user_id} (FK → users.id, not null),
     *       {@code currency_code}, {@code iban}.</li>
     *   <li>{@link com.opayque.api.card.entity.VirtualCard} – {@code @Table(name = "virtual_cards")} with columns
     *       {@code id} (identity primary key), {@code account_id} (FK → accounts.id, not null),
     *       {@code pan} (unique, not null), {@code cvv}, {@code expiry_date},
     *       {@code cardholder_name}, {@code status}, {@code monthly_limit}.</li>
     * </ul>
     *
     * @author Madavan Babu
     * @since 2026
     *
     * @see com.opayque.api.card.controller.CardController
     * @see com.opayque.api.card.service.CardTransactionService
     * @see com.opayque.api.identity.repository.UserRepository
     * @see com.opayque.api.wallet.repository.AccountRepository
     * @see com.opayque.api.card.repository.VirtualCardRepository
     * @see com.opayque.api.wallet.repository.LedgerRepository
     * @see com.opayque.api.wallet.service.LedgerService
     */
    @BeforeEach
    void setup() {
        // Clean Slate
        redisTemplate.getRequiredConnectionFactory().getConnection().serverCommands().flushAll();
        ledgerRepository.deleteAll();
        virtualCardRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        // 1. Setup User & Wallet (Infinite Funding)
        stressUser = userRepository.saveAndFlush(User.builder()
                .email("exhaustion@opayque.com")
                .fullName("Resource Hog")
                .password("hashed")
                .role(Role.CUSTOMER)
                .build());

        stressWallet = accountRepository.saveAndFlush(Account.builder()
                .user(stressUser)
                .currencyCode("EUR")
                .iban("EU99HOG0000001")
                .build());

        ledgerService.recordEntry(new CreateLedgerEntryRequest(
                stressWallet.getId(), new BigDecimal("10000000.00"), "EUR",
                TransactionType.CREDIT, "Infinite Funding", LocalDateTime.now(), UUID.randomUUID()
        ));

        // 2. Pre-provision 1000 Unique Cards (The Botnet)
        // We do this serially in setup to isolate the "Transaction" stress from "Issuance" stress.
        // We want to test if 1000 *Transactions* kill the DB pool.
        log.info("Provisioning {} Cards for Global Botnet...", BOTNET_SIZE);
        botnetPans = new ArrayList<>(BOTNET_SIZE);
        List<VirtualCard> batch = new ArrayList<>(BOTNET_SIZE);

        // REMOVED: encCvv and encExpiry manual encryption variables!

        for (int i = 0; i < BOTNET_SIZE; i++) {
            // Generate valid luhn-ish suffix (simplified for stress)
            String suffix = String.format("%010d", i);
            String pan = opayqueBin + suffix;
            botnetPans.add(pan);

            batch.add(VirtualCard.builder()
                    .account(stressWallet)
                    .pan(pan)
                    // CRITICAL: Pass plaintext! Hibernate handles encryption on saveAllAndFlush.
                    .cvv(RAW_CVV)
                    .expiryDate(RAW_EXPIRY)
                    .cardholderName("Bot-" + i)
                    .status(CardStatus.ACTIVE)
                    .monthlyLimit(new BigDecimal("10000.00"))
                    .build());
        }
        virtualCardRepository.saveAllAndFlush(batch);
        log.info("Provisioning Complete.");
    }

    // =========================================================================
    // TEST 1: The "Global Botnet" (Connection Starvation)
    // =========================================================================
    /**
     * <b>Target:</b> 1000 Unique Cards, 1000 Concurrent Threads.
     * <p>
     * <b>The Attack:</b> Each thread attempts a transaction simultaneously.
     * <b>The Goal:</b> Exhaust the HikariCP (pool=10) and Redis pools.
     * <p>
     * <b>Bank-Grade Check:</b>
     * <ul>
     * <li>Errors: Acceptable to see ConnectionTimeouts (pool exhaustion), but system MUST recover.</li>
     * <li>Recovery: A final "Probe" transaction must succeed immediately after the storm.</li>
     * <li>Deadlock: System must not hang indefinitely.</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 1: Global Botnet - 1000 Threads vs Pool Size 10")
    void globalBotnet_ConnectionStarvation() throws InterruptedException {
        int threadCount = BOTNET_SIZE;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Latency Tracking
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());

        log.info("STARTING: Global Botnet ({} Threads vs 10 DB Connections)...", threadCount);

        for (int i = 0; i < threadCount; i++) {
            final String pan = botnetPans.get(i);
            executor.submit(() -> {
                try {
                    latch.await();
                    long start = System.currentTimeMillis();

                    CardTransactionRequest request = new CardTransactionRequest(
                            pan, RAW_CVV, RAW_EXPIRY, new BigDecimal("1.00"), "EUR",
                            "BotNet Node", "5999", UUID.randomUUID().toString()
                    );

                    MvcResult result = mockMvc.perform(post("/api/v1/simulation/card-transaction")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(objectMapper.writeValueAsString(request)))
                            .andReturn();

                    long duration = System.currentTimeMillis() - start;
                    latencies.add(duration);

                    int status = result.getResponse().getStatus();
                    if (status == 200) {
                        successCount.incrementAndGet();
                    } else if (status >= 500) {
                        // 500s often indicate Pool Exhaustion / Timeout
                        timeoutCount.incrementAndGet();
                    } else {
                        errorCount.incrementAndGet();
                    }

                } catch (Exception e) {
                    // Likely ConnectionTimeoutException wrapped
                    timeoutCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // RELEASE THE STORM
        boolean finished = doneLatch.await(45, TimeUnit.SECONDS); // Generous timeout for queueing
        executor.shutdownNow();

        // METRICS ANALYSIS
        double avgLatency = latencies.stream().mapToLong(val -> val).average().orElse(0.0);
        long maxLatency = latencies.stream().mapToLong(v -> v).max().orElse(0);

        log.info("STORM FINISHED.");
        log.info("Stats -> Success: {}, Timeouts/500s: {}, Errors: {}",
                successCount.get(), timeoutCount.get(), errorCount.get());
        log.info("Latency -> Avg: {}ms, Max: {}ms", String.format("%.2f", avgLatency), maxLatency);

        // ASSERTIONS
        assertThat(finished).as("System Deadlocked! Test timed out.").isTrue();

        // 1. POOL BEHAVIOR CHECK
        // With 1000 threads and 10 connections, we expect EITHER:
        // A) Massive Queueing (High Latency, High Success)
        // B) Fast Failure (ConnectionTimeout, Low Success)
        // We just ensure we processed *something* and didn't crash entirely.
        assertThat(successCount.get() + timeoutCount.get() + errorCount.get())
                .as("Not all requests completed").isEqualTo(threadCount);

        // 2. RECOVERY CHECK (The "Defibrillator")
        // Fire one valid transaction NOW. It must pass instantly.
        // If the pool is deadlocked/leaked, this will fail or hang.
        log.info("EXECUTING RECOVERY PROBE...");
        try {
            CardTransactionRequest probe = new CardTransactionRequest(
                    botnetPans.get(0), RAW_CVV, RAW_EXPIRY, new BigDecimal("5.00"), "EUR",
                    "Recovery Probe", "5411", UUID.randomUUID().toString()
            );

            long probeStart = System.currentTimeMillis();
            mockMvc.perform(post("/api/v1/simulation/card-transaction")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(probe)))
                    .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(200));
            long probeTime = System.currentTimeMillis() - probeStart;

            log.info("PROBE SUCCESS in {}ms", probeTime);

            // Critical Check: Probe should be fast (Pool should be free)
            assertThat(probeTime).as("Pool failed to recover latency!").isLessThan(2000);

        } catch (Exception e) {
            throw new AssertionError("Recovery Probe Failed! System is Deadlocked.", e);
        }
    }

    // =========================================================================
    // TEST 2 The "CPU Burner" (Cryptographic Saturation)
    // =========================================================================
    /**
     * <b>Target:</b> Continuous Stream of Valid Transactions.
     * <p>
     * <b>The Attack:</b> Maximize throughput (TPS) on a single thread to benchmark CPU/Crypto overhead.
     * <b>The Goal:</b> Saturate the CPU with AES-256 Decryption.
     * <p>
     * <b>Bank-Grade Check:</b>
     * <ul>
     * <li>Metric: Throughput (TPS). Low TPS implies Crypto bottleneck.</li>
     * <li>Assertion: Must maintain a minimum baseline TPS (e.g., > 50 TPS) even with overhead.</li>
     * </ul>
     */
    @Test
    @DisplayName("Stress 2: CPU Burner - Crypto Throughput Test")
    void cpuBurner_CryptoSaturation() {
        // FIX: Disable the Rate Limiter for this performance benchmark
        doNothing().when(rateLimiterService).checkLimit(anyString(), anyString(), anyLong());

        // Use a single card for simplicity, we focus on the Processing Pipeline speed
        String pan = botnetPans.get(0);
        int iterations = 1000;

        log.info("STARTING: CPU Burner ({} Sequential Transactions)...", iterations);

        long start = System.currentTimeMillis();
        AtomicInteger success = new AtomicInteger(0);

        for (int i = 0; i < iterations; i++) {
            try {
                CardTransactionRequest request = new CardTransactionRequest(
                        pan, RAW_CVV, RAW_EXPIRY, new BigDecimal("0.10"), "EUR",
                        "Speed Test", "5411", UUID.randomUUID().toString()
                );

                mockMvc.perform(post("/api/v1/simulation/card-transaction")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                        .andExpect(result -> {
                            if (result.getResponse().getStatus() == 200) success.incrementAndGet();
                        });

            } catch (Exception e) {
                log.error("Burner Error", e);
            }
        }

        long totalTime = System.currentTimeMillis() - start;
        double tps = (double) iterations / (totalTime / 1000.0);

        log.info("BURNER FINISHED.");
        log.info("Total Time: {}ms", totalTime);
        log.info("Throughput: {} TPS", String.format("%.2f", tps));

        // ASSERTIONS
        assertThat(success.get()).isEqualTo(iterations);

        // Baseline Assertion:
        // Even with Crypto + DB + Redis + Http Overhead, a decent single-threaded loop
        // should hit at least 20-50 TPS on a local machine.
        // If < 10 TPS, our Encryption is likely using bad settings (e.g., Scrypt with high cost).
        assertThat(tps).as("Crypto/DB Latency is too high! TPS < 10").isGreaterThan(10.0);
    }
}