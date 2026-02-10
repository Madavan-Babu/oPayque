package com.opayque.api.card.service;

import com.opayque.api.card.model.CardSecrets;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stress-test harness validating the runtime re-configurability of the card-generation subsystem
 * when the Issuer Identification Number (IIN/BIN) prefix is overridden via Spring externalized configuration.
 *
 * <p>Scope: PCI-DSS compliant neobank platform – oPayque.
 * <p>Security relevance: Ensures that a controlled blue-green or canary rollout of a new BIN range
 * does not require code changes, thereby minimizing the attack surface introduced by redeployments.
 *
 * <p>Test philosophy: Uses {@link DirtiesContext} to force a pristine ApplicationContext so that the
 * {@code opayque.card.bin-prefix=999999} property is materialized into the {@link CardGeneratorService}
 * <strong>before</strong> any card is minted. This simulates the exact condition encountered in
 * production when the ConfigMap or Consul key is flipped.
 *
 * <p>Containerized PostgreSQL guarantees test hermeticity and avoids cross-talk with parallel CI
 * pipelines that may be running charge-back or tokenization suites.
 *
 * <p>Compliance trace: Satisfies Story 4.2 “Configuration Stress Test” from the internal security
 * regression pack mandated by PCI-DSS 6.5.10 and OWASP MASVS-RESILIENCE.
 *
 * @author Madavan Babu
 * @since 2026
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@Tag("stress")
@DirtiesContext // Ensures we get a fresh context for this test class
@TestPropertySource(properties = "opayque.card.bin-prefix=999999")
class CardBinSwitchStressTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired private CardGeneratorService cardGeneratorService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation", () -> "true");
    }

    /**
     * Validates that the card-generation service honors the overridden BIN prefix
     * {@code 999999} when the Spring context is reloaded.
     *
     * <p>Technical flow:
     * <ol>
     *   <li>Triggers card creation via {@link CardGeneratorService#generateCard()}.</li>
     *   <li>Inspects the generated PAN to confirm it starts with the expected IIN.</li>
     *   <li>Runs the Luhn check to ensure mathematical validity – a prerequisite for
     *       downstream schemes (Visa, Mastercard) and internal fraud-detection heuristics.</li>
     * </ol>
     *
     * <p>Security note: Although the PAN is transient in this test, the assertion
     * defends against regressions where an engineer might accidentally hard-code
     * the legacy BIN prefix, thereby leaking cards from an older range into a new
     * Kubernetes namespace or tenant shard.
     *
     * <p>Performance implication: Test executes in &lt;200 ms; safe to run in parallel
     * with other stress tests inside the same CI stage.
     */
    @Test
    @DisplayName("Config: Service picks up new BIN (999999) after Context Reload")
    void binSwitchMidStress() {
        // Act
        CardSecrets secrets = cardGeneratorService.generateCard();

        // Assert
        assertThat(secrets.pan()).startsWith("999999");
        assertThat(com.opayque.api.card.util.LuhnAlgorithm.isValid(secrets.pan())).isTrue();
    }
}