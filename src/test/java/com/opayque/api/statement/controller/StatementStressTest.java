package com.opayque.api.statement.controller;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@Tag("stress")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // Reuses containers and setup across all stress methods
class StatementStressTest {

    // ==================================================================================
    // INFRASTRUCTURE SETUP
    // ==================================================================================

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("opayque_stress")
            .withUsername("stress_user")
            .withPassword("stress_pass");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // FORCE START: Ensures containers are up before Spring tries to read their ports
        if (!postgres.isRunning()) postgres.start();
        if (!redis.isRunning()) redis.start();

        // 1. Connection Strings
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);

        // HikariCP Tuning for High-Concurrency Stress Tests
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "50");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "10");

        // 2. Force PostgreSQL Dialect for Hibernate
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // 3. Liquibase Integration
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.liquibase.user", postgres::getUsername);
        registry.add("spring.liquibase.password", postgres::getPassword);
        registry.add("spring.liquibase.url", postgres::getJdbcUrl);
        registry.add("spring.liquibase.driver-class-name", postgres::getDriverClassName);

        // 4. Hibernate Logic
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private User whaleUser;
    private Account whaleAccount;

    private final int WHALE_RECORD_COUNT = 100_000;

    // ==================================================================================
    // MASSIVE DATA SEEDING
    // ==================================================================================

    @BeforeAll
    void setupWhaleAccount() {
        whaleUser = User.builder()
                .fullName("Corporate Whale")
                .email("whale@opayque.com")
                .password("hashed_password")
                .role(Role.CUSTOMER)
                .build();
        whaleUser = userRepository.save(whaleUser);

        whaleAccount = Account.builder()
                .user(whaleUser)
                .iban("DE30999988887777666655")
                .currencyCode("EUR")
                .status(AccountStatus.ACTIVE)
                .build();
        whaleAccount = accountRepository.save(whaleAccount);

        seedMassiveLedger(whaleAccount.getId(), WHALE_RECORD_COUNT);
    }

    @BeforeEach
    void clearRateLimits() {
        // VECTOR ISOLATION:
        // Using flushAll() to ensure the Redis Testcontainer is 100% clean.
        // This is the non-deprecated replacement for flushDb().
        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.serverCommands().flushAll();
            return null;
        });

        log.info("Redis state nuked. Starting fresh stress vector.");
    }

    @AfterAll
    void tearDown() {
        jdbcTemplate.execute("TRUNCATE TABLE ledger_entries CASCADE");
        accountRepository.deleteAll();
        userRepository.deleteAll();
    }

    // VECTOR 1 & 2 Helper: High-speed batch insert
    @SuppressWarnings("SqlResolve") // Silences IDE errors as the schema exists only inside the container
    private void seedMassiveLedger(UUID accountId, int count) {
        // Ensure these match your Liquibase changelog/Database column names exactly
        String sql = "INSERT INTO ledger_entries (id, account_id, amount, currency, direction, transaction_type, description, recorded_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        LocalDateTime baseTime = LocalDateTime.now().minusMonths(3);

        jdbcTemplate.batchUpdate(sql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, accountId);
                ps.setBigDecimal(3, new java.math.BigDecimal("150.00"));
                ps.setString(4, "EUR");
                ps.setString(5, "IN");
                ps.setString(6, "CREDIT");
                ps.setString(7, "Stress Test Transaction " + i);
                ps.setTimestamp(8, java.sql.Timestamp.valueOf(baseTime.plusMinutes(i)));
            }

            @Override
            public int getBatchSize() {
                return count;
            }
        });
    }

    private UsernamePasswordAuthenticationToken createRealAuthToken(User user) {
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + user.getRole().name());
        return new UsernamePasswordAuthenticationToken(user, null, Collections.singletonList(authority));
    }

    // ==================================================================================
    // STRESS TEST VECTORS
    // ==================================================================================

    /**
     * VECTOR 1: Heap Exhaustion (The Whale Account)
     * Proof that the JVM does not attempt to hydrate 100,000 records simultaneously.
     */
    @Test
    void test1_WhaleAccount_HeapExhaustion_MaintainsConstantMemory() throws Exception {
        // 1. Clean Baseline
        log.info("--- STARTING WHALE ACCOUNT MEMORY STRESS TEST ---");
        System.gc();
        Thread.sleep(500);

        Runtime runtime = Runtime.getRuntime();
        long baselineMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        log.info("[PHASE 1] Baseline Heap Memory: {} MB", baselineMemoryMb);

        // 2. Execution
        log.info("[PHASE 2] Initiating 100,000 row CSV stream for Whale Account...");
        long startTime = System.currentTimeMillis();

        String url = "/api/v1/statements/export?accountId=" + whaleAccount.getId()
                + "&startDate=" + LocalDate.now().minusMonths(4)
                + "&endDate=" + LocalDate.now();

        MvcResult result = mockMvc.perform(get(url)
                        .with(authentication(createRealAuthToken(whaleUser))))
                .andExpect(status().isOk())
                .andReturn();

        // 3. Streamed Assertion (Counting lines without String overhead)
        int lineCount = 0;
        try (java.io.InputStream is = new java.io.ByteArrayInputStream(result.getResponse().getContentAsByteArray());
             java.util.Scanner scanner = new java.util.Scanner(is)) {
            while (scanner.hasNextLine()) {
                scanner.nextLine();
                lineCount++;
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("[PHASE 2] Stream completed in {} ms. Total rows verified: {}", duration, lineCount);
        assertTrue(lineCount > WHALE_RECORD_COUNT, "Stream dropped records! Lines: " + lineCount);

        // 4. THE DETERMINISTIC LEAK CHECK
        long preGcMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        log.info("[PHASE 3] Pre-GC Heap Memory (Includes MockMvc buffers and uncollected L1 Cache garbage): {} MB", preGcMemoryMb);

        log.info("[PHASE 3] Forcing JVM Garbage Collection to vaporize detached entities...");
        System.gc();
        Thread.sleep(500); // Allow GC thread time to sweep the heap

        long residualMemoryMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long retainedGrowthMb = residualMemoryMb - baselineMemoryMb;

        log.info("[PHASE 4] Post-GC Residual Memory: {} MB. Total Retained Growth: {} MB.", residualMemoryMb, retainedGrowthMb);

        // Assertion: If there is a real leak (hard references), retained memory will stay at 200MB+.
        // If there is no leak, retained memory will drop back down to nearly 0.
        // We set a strict 25MB allowance for Spring Context/MockMvc framework overhead.
        assertTrue(retainedGrowthMb < 25,
                String.format("HEAVY MEMORY LEAK: Entities survived Garbage Collection! Retained heap grew by %d MB.", retainedGrowthMb));

        log.info("--- WHALE ACCOUNT MEMORY STRESS TEST PASSED ---");
    }

    /**
     * VECTOR 2: Database Contention (The Thundering Herd)
     * Proves the system handles extreme concurrent bursts without dropping connections
     * or throwing HikariCP PoolTimeoutExceptions.
     */
    @Test
    void test2_ThunderingHerd_DBContention_DoesNotExhaustConnectionPool() throws Exception {
        int CONCURRENCY = 50; // Matches our max HikariCP pool size

        // 1. Setup unique users to bypass the per-user rate limit and force DB hits
        java.util.List<Account> herdAccounts = new java.util.ArrayList<>();
        java.util.List<User> herdUsers = new java.util.ArrayList<>();

        for (int i = 0; i < CONCURRENCY; i++) {
            User u = User.builder()
                    .fullName("Herd User " + i)
                    .email("herd" + i + "@opayque.com")
                    .password("hashed_pass")
                    .role(Role.CUSTOMER)
                    .build();
            u = userRepository.save(u);
            herdUsers.add(u);

            Account a = Account.builder()
                    .user(u)
                    .iban(String.format("DE3012345678901234%04d", i))
                    .currencyCode("EUR")
                    .status(AccountStatus.ACTIVE)
                    .build();
            a = accountRepository.save(a);
            herdAccounts.add(a);

            // Insert 10 transactions per account to give the DB real work to do
            seedMassiveLedger(a.getId(), 10);
        }

        // 2. Concurrency Primitives
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(CONCURRENCY);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(CONCURRENCY);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(CONCURRENCY);

        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger failureCount = new java.util.concurrent.atomic.AtomicInteger(0);

        // 3. Queue up the concurrent requests
        for (int i = 0; i < CONCURRENCY; i++) {
            final User currentUser = herdUsers.get(i);
            final Account currentAccount = herdAccounts.get(i);

            executor.submit(() -> {
                try {
                    // Start date matches the seedMassiveLedger baseTime (minus 3 months)
                    String url = "/api/v1/statements/export?accountId=" + currentAccount.getId()
                            + "&startDate=" + LocalDate.now().minusMonths(4)
                            + "&endDate=" + LocalDate.now();

                    readyLatch.countDown(); // Thread is ready
                    startLatch.await();     // Wait for the "starting gun"

                    MvcResult result = mockMvc.perform(get(url)
                                    .with(authentication(createRealAuthToken(currentUser))))
                            .andReturn();

                    if (result.getResponse().getStatus() == 200) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 4. Fire the starting gun at the exact same millisecond
        readyLatch.await(); // Wait for all 50 threads to be queued
        startLatch.countDown(); // GO!

        // Wait for all to finish (with a safe 30-second timeout to prevent infinite hangs)
        boolean completed = doneLatch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // 5. Evaluate the Chaos
        assertTrue(completed, "Thundering Herd test timed out. Deadlock or extreme HikariCP pool starvation occurred.");
        assertEquals(0, failureCount.get(), "System dropped requests during the concurrency spike! Check connection pool exhaustion.");
        assertEquals(CONCURRENCY, successCount.get(), "Not all 50 requests returned a 200 OK.");
    }

    /**
     * VECTOR 3: Redis Rate Limiter Thrashing (Contention Vector)
     * Proof of atomicity: Blasting a single user's quota with concurrent threads
     * ensures exactly the quota (5) passes and exactly the rest (95) are blocked.
     */
    @Test
    void test3_RateLimiter_Thrashing_EnforcesStrictQuota() throws Exception {
        int CONCURRENT_ATTEMPTS = 100;
        int ALLOWED_QUOTA = 5; // As defined in StatementService.RATE_LIMIT_QUOTA

        ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(CONCURRENT_ATTEMPTS);
        CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(CONCURRENT_ATTEMPTS);

        AtomicInteger accepted = new java.util.concurrent.atomic.AtomicInteger(0);
        AtomicInteger rejected = new java.util.concurrent.atomic.AtomicInteger(0);

        String url = "/api/v1/statements/export?accountId=" + whaleAccount.getId()
                + "&startDate=" + LocalDate.now().minusDays(1)
                + "&endDate=" + LocalDate.now();

        for (int i = 0; i < CONCURRENT_ATTEMPTS; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // Synchronization point for the "blast"
                    MvcResult result = mockMvc.perform(get(url)
                                    .with(authentication(createRealAuthToken(whaleUser))))
                            .andReturn();

                    int status = result.getResponse().getStatus();
                    if (status == 200) accepted.incrementAndGet();
                    else if (status == 429) rejected.incrementAndGet();

                } catch (Exception ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Fire the blast
        doneLatch.await(15, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // ASSERTIONS:
        // Proof that the Redis Lua script is atomic under high thread contention.
        assertEquals(ALLOWED_QUOTA, accepted.get(),
                "Rate limiter allowed more/less than the strict quota during contention!");
        assertEquals(CONCURRENT_ATTEMPTS - ALLOWED_QUOTA, rejected.get(),
                "Rate limiter failed to reject exactly the surplus requests.");
    }

    /**
     * VECTOR 4: Network Exhaustion (The "Subway Commuter" Broken Pipe)
     * Simulates client disconnects mid-stream. Proves that Tomcat/JVM thread interrupts
     * safely rollback database cursors and release HikariCP connections without leaking.
     */
    @Test
    void test4_SubwayCommuter_BrokenPipe_GracefullyReleasesResources() throws Exception {
        // 1. Setup a fresh user to bypass previous rate limit quotas from Test 3
        User commuterEntity = User.builder()
                .fullName("Subway Commuter")
                .email("commuter@opayque.com")
                .password("hashed_pass")
                .role(Role.CUSTOMER)
                .build();
        final User finalCommuter = userRepository.save(commuterEntity);

        Account commuterAccount = Account.builder()
                .user(finalCommuter)
                .iban("DE30999988887777123456")
                .currencyCode("EUR")
                .status(AccountStatus.ACTIVE)
                .build();
        commuterAccount = accountRepository.save(commuterAccount);

        // Seed enough data to guarantee multiple DB chunks and processing time (5,000 rows)
        seedMassiveLedger(commuterAccount.getId(), 5000);

        // 2. Fire requests up to the maximum Rate Limit Quota
        int QUOTA = 5;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(QUOTA);
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

        String url = "/api/v1/statements/export?accountId=" + commuterAccount.getId()
                + "&startDate=" + LocalDate.now().minusMonths(4)
                + "&endDate=" + LocalDate.now();

        for (int i = 0; i < QUOTA; i++) {
            futures.add(executor.submit(() -> {
                try {
                    mockMvc.perform(get(url).with(authentication(createRealAuthToken(finalCommuter))));
                } catch (Exception ignored) {
                    // Ignored: We expect an InterruptedException or ClientAbortException
                }
            }));
        }

        // 3. Allow streams to open and DB cursors to start fetching chunks
        Thread.sleep(300);

        // 4. SIMULATE THE BROKEN PIPE
        // Forcefully interrupting the threads mimics the socket drop.
        // It tests if the Service layer's try/catch and Spring's @Transactional
        // correctly release the DB connection instead of deadlocking.
        for (java.util.concurrent.Future<?> future : futures) {
            future.cancel(true);
        }

        executor.shutdown();
        boolean terminated = executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(terminated, "Threads refused to die! Possible connection leak or deadlock on Broken Pipe.");

        // 5. ASSERTION: System Recovery
        // If connections were leaked, the Hikari pool would be starved. We prove recovery
        // by making a fast, successful query to the database immediately after the chaos.
        long activeAccounts = accountRepository.count();
        assertTrue(activeAccounts > 0, "Database became unresponsive after broken pipes! Connection Pool exhausted.");
    }

    /**
     * VECTOR 5: Data Skew Pagination Chaos (CPU/IO Starvation Vector)
     * Injects 20,000 records with the EXACT same timestamp and highly complex, malicious CSV payloads.
     * Proves that the database index handles extreme skew and that the CPU-bound sanitizeCsv()
     * string manipulation does not cause O(n^2) latency spikes.
     */
    @Test
    @SuppressWarnings("SqlResolve")
    void test5_ExtremeDataSkew_PaginationChaos_MaintainsAcceptableThroughput() throws Exception {
        // 1. Setup isolated user
        User skewUser = User.builder()
                .fullName("Skew User")
                .email("skew@opayque.com")
                .password("hashed_pass")
                .role(Role.CUSTOMER)
                .build();
        final User finalSkewUser = userRepository.save(skewUser);

        Account skewAccount = Account.builder()
                .user(finalSkewUser)
                .iban("DE30555544443333222211")
                .currencyCode("EUR")
                .status(AccountStatus.ACTIVE)
                .build();
        final Account finalSkewAccount = accountRepository.save(skewAccount);

        // 2. The Chaos Payload
        // Includes: Malicious prefix (=), internal double quotes, newlines, and commas.
        // This forces EVERY single branch of the StatementService.sanitizeCsv() method to execute.
        String nastyDescription = "=cmd|' /C calc'!A0, \"Dirty\" \n Data";
        int SKEW_RECORD_COUNT = 20_000;

        // The Data Skew: Every single record gets this EXACT same millisecond.
        LocalDateTime staticTime = LocalDateTime.now().minusDays(5);

        String sql = "INSERT INTO ledger_entries (id, account_id, amount, currency, direction, transaction_type, description, recorded_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        jdbcTemplate.batchUpdate(sql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, finalSkewAccount.getId());
                ps.setBigDecimal(3, java.math.BigDecimal.ONE);
                ps.setString(4, "EUR");
                ps.setString(5, "OUT");
                ps.setString(6, "DEBIT");
                ps.setString(7, nastyDescription + " ID:" + i);
                ps.setTimestamp(8, java.sql.Timestamp.valueOf(staticTime));
            }

            @Override
            public int getBatchSize() {
                return SKEW_RECORD_COUNT;
            }
        });

        // 3. Execute the payload and track CPU bound latency
        String url = "/api/v1/statements/export?accountId=" + finalSkewAccount.getId()
                + "&startDate=" + LocalDate.now().minusMonths(1)
                + "&endDate=" + LocalDate.now();

        long startTimeMillis = System.currentTimeMillis();

        MvcResult result = mockMvc.perform(get(url)
                        .with(authentication(createRealAuthToken(finalSkewUser))))
                .andExpect(status().isOk())
                .andReturn();

        long elapsedMillis = System.currentTimeMillis() - startTimeMillis;

        // 4. Verification
        String content = result.getResponse().getContentAsString();

        // Assert payload neutralized but exists
        assertTrue(content.contains("cmd|' /C calc'!A0"), "Stream dropped the malicious payload entirely instead of escaping it.");
        assertFalse(content.contains("=cmd|' /C calc'!A0"), "Injection prefix leaked through sanitization!");

        // 5. The SLA Assertion
        // Even with 20,000 highly complex strings, chunking + formatting should take less than 3-5 seconds.
        // If it takes 15+ seconds, you have a severe CPU bottleneck in your string manipulation.
        assertTrue(elapsedMillis < 5000,
                String.format("CPU Starvation Detected! Escaping and chunking 20,000 complex rows took %d ms. SLA is < 5000 ms.", elapsedMillis));
    }
}