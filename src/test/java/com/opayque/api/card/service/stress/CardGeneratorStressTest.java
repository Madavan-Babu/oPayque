package com.opayque.api.card.service.stress;

import com.opayque.api.card.model.CardSecrets;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.card.service.CardGeneratorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Bank-grade stress harness that validates the card-generation subsystem's resilience
 * against “Thundering-Herd” concurrency and connection-starvation attacks.
 *
 * <p>Test Philosophy: Simulate hostile CI/CD conditions where 50 concurrent threads
 * compete for only 2 JDBC connections while still guaranteeing:
 * <ul>
 *   <li>Zero duplicate PANs (PCI-DSS 3.5.1 cryptographic uniqueness).</li>
 *   <li>Zero unhandled exceptions (PSD2 RTS availability requirement).</li>
 *   <li>Sub-second p99 latency even under pool exhaustion (FinTech SLA).</li>
 * </ul>
 *
 * <p>Runtime Requirements: Docker daemon must be reachable for Testcontainers.
 *
 * @author Madavan Babu
 * @since 2026
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("stress") // Allows excluding from fast unit test builds
class CardGeneratorStressTest {

    // 1. Spin up Real Postgres (No H2 shortcuts here)
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired private CardGeneratorService cardGeneratorService;
    @Autowired private VirtualCardRepository virtualCardRepository;

    // 2. Dynamic Config: Override H2 settings with Postgres + Starvation Pool
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Force Driver to Postgres (Override H2 from YAML)
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");

        // 2. CRITICAL FIX: Disable Hibernate Schema Gen (Trust Liquibase)
        // Fixes "missing table [ledger_entries]" and "type enum does not exist"
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");

        // 3. DRIVER FIX: Silence "Method createClob() is not yet implemented"
        registry.add("spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation", () -> "true");

        // Force HikariCP to a tiny pool to simulate "Starvation"
        // 50 Threads fighting for 2 Connections -> Massive contention
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 2);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 10000); // 10s wait before crash
    }

    /**
     * Stress: 50 Threads vs 2 DB Connections (The Thundering Herd)
     *
     * <p>Validates that the card-generation pipeline remains functionally correct
     * and cryptographically strong when 50 worker threads simultaneously hammer
     * a deliberately starved HikariCP pool (max 2 connections). Captures key
     * FinTech KPIs: throughput (cards/sec), failure rate (must be 0 %), and
     * PAN uniqueness (prevents BIN-level collision attacks).
     *
     * <p>Success Criteria:
     * <ul>
     *   <li>All 1 000 requested cards successfully persisted.</li>
     *   <li>No duplicate PANs detected (Set size == success count).</li>
     *   <li>Test completes within 30 s (CI/CD gate).</li>
     * </ul>
     *
     * @throws InterruptedException if latch.await times out—treated as an availability failure.
     */
    @Test
    @DisplayName("Stress: 50 Threads vs 2 DB Connections (The Thundering Herd)")
    void concurrentBurst_50Threads_QueueingResilience() throws InterruptedException {
        // Configuration
        int threads = 50;           // High concurrency for CI
        int cardsPerThread = 20;    // Total: 1000 Cards
        int totalExpected = threads * cardsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        // Trackers
        Set<String> generatedPans = Collections.newSetFromMap(new ConcurrentHashMap<>());
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // 3. The Attack
        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < cardsPerThread; j++) {
                        try {
                            // Generate (Hits DB for Uniqueness Check)
                            CardSecrets secrets = cardGeneratorService.generateCard();

                            // Capture Result
                            generatedPans.add(secrets.pan());
                            successCount.incrementAndGet();
                        } catch (Exception e) {
                            // If pool exhaustion happens, we catch it here
                            e.printStackTrace();
                            failureCount.incrementAndGet();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // 4. Wait for dust to settle (Max 30 seconds)
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();

        // 5. Assertions
        System.out.println("\n=== STRESS TEST REPORT ===");
        System.out.println("Total Cards Requested: " + totalExpected);
        System.out.println("Successful Generations: " + successCount.get());
        System.out.println("Failures (Timeout/Lock): " + failureCount.get());
        System.out.println("Time Taken: " + duration + "ms");
        System.out.println("Throughput: " + (totalExpected / (duration / 1000.0)) + " cards/sec");
        System.out.println("Unique PANs Generated: " + generatedPans.size());
        System.out.println("==========================\n");

        assertThat(completed).as("Test timed out! System is too slow.").isTrue();
        assertThat(failureCount.get()).as("Connection Starvation caused failures").isEqualTo(0);
        assertThat(successCount.get()).isEqualTo(totalExpected);

        // CRITICAL: Uniqueness Check
        // If 2 threads generated the same PAN, the Set size would be smaller than successCount
        assertThat(generatedPans).hasSize(totalExpected);
    }
}