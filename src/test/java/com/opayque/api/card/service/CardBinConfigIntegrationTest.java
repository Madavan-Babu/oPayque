package com.opayque.api.card.service;

import com.opayque.api.card.model.CardSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PCI-DSS compliant integration test verifying that runtime reconfiguration of the
 * Bank Identification Number (BIN) prefix via Spring {@code @TestPropertySource}
 * correctly propagates into the {@link CardGeneratorService} bean without
 * “context-dirtying” side-effects that could compromise parallel test isolation.
 *
 * <p>Security relevance: the BIN is the first six digits of the PAN and is
 * considered non-sensitive, yet it drives routing, interchange fees and fraud
 * detection rules. Ensuring it can be centrally overridden is critical for
 * A/B testing, geo-fencing and regulatory sandbox scenarios.
 *
 * <p>Test philosophy: uses an isolated Spring context (profile “test”) so that
 * the override {@code opayque.card.bin-prefix=654321} does not leak into
 * production-like slices or other integration suites.
 *
 * @author Madavan Babu
 * @since 2026
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "opayque.card.bin-prefix=654321")
class CardBinConfigIntegrationTest {

    @Autowired
    private CardGeneratorService cardGeneratorService;

    /**
     * Asserts that the card-generation pipeline honors the externally supplied BIN
     * prefix {@code 654321} instead of the default {@code 171103}, while still
     * producing a mathematically valid 16-digit PAN that passes the Luhn check.
     *
     * <p>FinTech impact: guarantees that downstream schemes (interchange,
     * tokenization, 3-DS) receive syntactically valid PANs that align with
     * sandbox or partner routing tables.
     *
     * <p>Security note: the test only inspects the non-sensitive six-digit prefix;
     * the remaining PAN digits and the encrypted payload are never logged or
     * asserted, maintaining PCI-DSS compliance.
     *
     * @see com.opayque.api.card.util.LuhnAlgorithm#isValid(String)
     */
    @Test
    @DisplayName("Config: Should generate PANs starting with overridden BIN (654321)")
    void shouldHonorBinPrefixFromConfiguration() {
        CardSecrets secrets = cardGeneratorService.generateCard();

        // Assert it starts with the overridden value, NOT the default "171103"
        assertThat(secrets.pan()).startsWith("654321");

        // Ensure the rest of the logic (Luhn) still holds
        assertThat(com.opayque.api.card.util.LuhnAlgorithm.isValid(secrets.pan())).isTrue();
    }
}