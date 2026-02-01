package com.opayque.api.wallet.service;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.integration.currency.CurrencyExchangeService;
import com.opayque.api.wallet.dto.CreateLedgerEntryRequest;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.TransactionType;
import com.opayque.api.wallet.repository.AccountRepository;
import com.opayque.api.wallet.repository.LedgerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=none"
})
@Testcontainers
@Tag("stress") // Allows us to exclude this from fast builds if needed
class LedgerStressTest {

    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>(DockerImageName.parse("postgres:15-alpine"))
            .withEnv("POSTGRES_DB", "opayque_test")
            .withEnv("POSTGRES_USER", "ci_user")
            .withEnv("POSTGRES_PASSWORD", "ci_password")
            .withExposedPorts(5432);

    // Redis container is needed if Service context loads RedisConfig,
    // but here we might mock the Cache or use the embedded config from the Base Test.
    // For safety in a "Stress" test, let's assume standard context loading.
    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getFirstMappedPort() + "/opayque_test");
        registry.add("spring.datasource.username", () -> "ci_user");
        registry.add("spring.datasource.password", () -> "ci_password");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        // CRITICAL FIX: Override the H2 Driver from application-test.yaml
        // Without this, the test tries to connect to Postgres using the H2 Driver!
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // 2. THE SMOKING GUN FIX: Force PostgreSQL Dialect
        // Without this, Hibernate was generating H2 SQL, which failed to lock the rows in Postgres.
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        // FIX: HIKARI POOL SIZE
        // 50 Threads require at least 50 connections to strictly serialize without starving.
        // We set it to 60 to have a buffer.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "60");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "10");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "30000"); // 30s

        // FIX: DRIVER COMPATIBILITY
        // Suppresses the "createClob() not implemented" warning which might be dirtying connections
        registry.add("spring.jpa.properties.hibernate.jdbc.use_get_generated_keys", () -> "true");
        registry.add("spring.jpa.properties.hibernate.temp.use_jdbc_metadata_defaults", () -> "false");
    }

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private AccountRepository accountRepository;

    // NEW: We need this to satisfy the Foreign Key constraint
    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @MockitoBean
    private CurrencyExchangeService exchangeService;

    @BeforeEach
    void setup() {
        ledgerRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        // Mock Rates to avoid external calls during stress test
        when(exchangeService.getRate(anyString(), anyString())).thenReturn(BigDecimal.ONE);
    }

    @Test
    @DisplayName("Concurrency: Should process 50 parallel transactions without data loss (Thundering Herd)")
    void shouldProcessConcurrentTransactionsWithoutDataLoss() throws InterruptedException {

        // 1. Create a Parent User (Mandatory FK)
        // FIX: Remove manual .id(UUID.randomUUID()) so @GeneratedValue can work correctly
        User stressUser = User.builder()
                .email("stress.test@opayque.com")
                .password("hashed-dummy-pw")
                .fullName("Stress Tester")
                .role(Role.CUSTOMER)
                .build();
        // FLUSH FIX: Ensures User is physically in DB before threads start
        stressUser = userRepository.saveAndFlush(stressUser);

        // 2. Create the Account linked to the User
        // FIX: Remove manual .id(UUID.randomUUID()) to stay consistent
        Account account = Account.builder()
                .user(stressUser) // <--- THE FIX: Assign the User
                .currencyCode("USD")
                .iban("US001234567890")
                .build();

        // FLUSH FIX: Ensures Account is physically in DB before threads start
        account = accountRepository.saveAndFlush(account);
        UUID accountId = account.getId();



        int threadCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        // Act: Fire 50 threads simultaneously
        UUID finalAccountId = accountId;
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    ledgerService.recordEntry(new CreateLedgerEntryRequest(
                            finalAccountId,
                            BigDecimal.TEN, // $10.00
                            "USD",
                            TransactionType.CREDIT,
                            "Stress Test",
                            null
                    ));
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertThat(completed).as("Stress test timed out!").isTrue();

        // FIX: The "Hitchhiker" Sleep.
        // Give the DB 1000ms to flush the 50th commit to the aggregate view.
        // Without this, SELECT SUM() might run 1ms before the last row is visible.
        // FIX: The "Stabilization Buffer" - Increased to 1000ms
        // Ensures database MVCC snapshots are consistent before aggregation.
        Thread.sleep(1000);

        assertThat(exceptions).isEmpty();

        // FIX: Verify Persistence First
        assertThat(ledgerRepository.count())
                .as("Not all transactions were persisted!")
                .isEqualTo(50);


        BigDecimal balance = ledgerService.calculateBalance(accountId);
        // FIX: Always use isEqualByComparingTo for BigDecimals to ignore scale differences
        assertThat(balance.stripTrailingZeros())
                .as("Balance Mismatch")
                .isEqualByComparingTo(new BigDecimal("500").stripTrailingZeros());
    }

    @Test
    @DisplayName("Concurrency: Mixed Currency Load (Locking & Caching Check)")
    void shouldHandleMixedCurrencyConcurrency() throws InterruptedException {

        // 1. Create a Parent User
        //DONT PUT A FCKIN RANDOM UUID GENERATOR ON A DAMN ENTITY WHICH AUTO GENERATES IT!!!!!
        User mixedUser = User.builder()
            .email("mixed.test@opayque.com")
            .password("hashed-dummy-pw")
            .fullName("Mixed Tester")
            .role(Role.CUSTOMER)
            .build();
        mixedUser = userRepository.saveAndFlush(mixedUser);

        // 2. Create the Account linked to the User
        //DONT PUT A FCKIN RANDOM UUID GENERATOR ON A DAMN ENTITY WHICH AUTO GENERATES IT!!!!!
        Account eurAccount = Account.builder()
                .user(mixedUser) // <--- THE FIX
                .currencyCode("EUR")
                .iban("DE001234567890")
                .build();

        eurAccount = accountRepository.saveAndFlush(eurAccount);
        UUID accountId = eurAccount.getId();

        // Mock Rate for USD -> EUR
        when(exchangeService.getRate("USD", "EUR")).thenReturn(new BigDecimal("0.90")); // 1 USD = 0.90 EUR

        int threadCount = 50; // 25 USD, 25 EUR
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Throwable> exceptions = Collections.synchronizedList(new ArrayList<>());

        // Act
        for (int i = 0; i < threadCount; i++) {
            boolean isUsd = (i % 2 == 0);
            executor.submit(() -> {
                try {
                    ledgerService.recordEntry(new CreateLedgerEntryRequest(
                            accountId,
                            BigDecimal.TEN, // 10.00
                            isUsd ? "USD" : "EUR",
                            TransactionType.CREDIT,
                            "Mixed Stress",
                            null
                    ));
                } catch (Exception e) {
                    exceptions.add(e);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all to finish
        boolean completed = latch.await(10, TimeUnit.SECONDS);

        // CRITICAL FIX: Fail if timeout occurred
        assertThat(completed)
                .as("Stress test timed out! Possible Deadlock or Performance Regression.")
                .isTrue();

        // FIX: The "Hitchhiker" Sleep.
        // Give the DB 200ms to flush the 50th commit to the aggregate view.
        // Without this, SELECT SUM() might run 1ms before the last row is visible.
        Thread.sleep(1000);

        // Assert
        assertThat(exceptions).isEmpty();

        // FIX: Verify Persistence First
        assertThat(ledgerRepository.count())
                .as("Not all transactions were persisted!")
                .isEqualTo(50);

        // Math Check:
        // 25 txns * 10 EUR = 250 EUR
        // 25 txns * 10 USD * 0.90 Rate = 225 EUR
        // Total = 475 EUR
        BigDecimal balance = ledgerService.calculateBalance(accountId);
        // Use isEqualByComparingTo to be safe, but now our scales match!
        assertThat(balance.stripTrailingZeros())
                .as("Balance Mismatch")
                .isEqualByComparingTo(new BigDecimal("475").stripTrailingZeros());
    }
}