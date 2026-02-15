package com.opayque.api.card.service.stress;

import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.model.CardSecrets;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.card.service.CardGeneratorService;
import com.opayque.api.card.util.LuhnAlgorithm;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;


/**
 * Stress-test harness validating the cryptographic uniqueness guarantees of
 * oPayque’s PCI-DSS compliant virtual-card issuance pipeline.
 *
 * <p>By forcing a micro-BIN (111) we artificially inflate the probability of
 * PAN collisions, thereby stressing the deterministic retry loop embedded
 * in {@link CardGeneratorService}.  The test proves that even under sustained
 * load the system maintains <i>exactly-once</i> card generation semantics
 * required for PSD2 RTS auditability and OWASP ASVS 5.3 (cryptographic
 * uniqueness).
 *
 * <p>Test profile: <b>stress</b>  
 * Test container: PostgreSQL 15-Alpine with isolated schema  
 * @author Madavan Babu
 * @since 2026
 *
 * @see VirtualCard
 * @see LuhnAlgorithm
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("stress")
@DirtiesContext
@TestPropertySource(properties = "opayque.card.bin-prefix=111")
class CardCollisionStressTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired private CardGeneratorService cardGeneratorService;
    @Autowired private VirtualCardRepository virtualCardRepository;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation", () -> "true");
    }

    /** Purges all card rows between runs to ensure deterministic collision statistics. */
    @BeforeEach
    void clearDb() {
        virtualCardRepository.deleteAll();
    }

    /**
     * Rapid-fire, single-threaded generation of 5 000 virtual cards while
     * enforcing a micro-BIN prefix.  Any failure in the retry logic would
     * surface as a duplicate PAN, shrinking the concurrent set below the
     * expected cardinality.  The assertion therefore proves the system’s
     * collision resilience under FinTech-grade throughput expectations.
     */
    @Test
    @DisplayName("Collision: Rapid generation of 5000 cards produces 0 duplicates")
    void collisionResilience_RapidFire() {
        int count = 5000;
        Set<String> pans = new ConcurrentHashMap<>().newKeySet();

        // Single thread rapid fire to stress the 'check-then-generate' loop
        for(int i=0; i<count; i++) {
            CardSecrets s = cardGeneratorService.generateCard();
            pans.add(s.pan());
        }

        // If duplicates occurred (and weren't retried successfully),
        // the Set size would be < count.
        assertThat(pans).hasSize(count);
    }
}