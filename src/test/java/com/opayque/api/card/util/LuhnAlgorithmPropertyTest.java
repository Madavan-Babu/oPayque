package com.opayque.api.card.util;

import net.jqwik.api.*;
import net.jqwik.api.constraints.NumericChars;
import net.jqwik.api.constraints.StringLength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based chaos harness for the Luhn (Mod-10) checksum used to generate
 * PCI-DSS compliant Primary Account Numbers (PAN) within the oPayque neobank platform.
 *
 * <p>This test suite leverages Jqwik to execute thousands of randomized trials,
 * mathematically proving that the {@link LuhnAlgorithm} implementation is both
 * <strong>correct</strong> and <strong>robust</strong> against malicious or malformed
 * inputs that could otherwise bypass downstream fraud-detection rules or
 * duplicate PAN checks.
 *
 * <p>Key security invariants verified:
 * <ul>
 *   <li>Every 15-digit numeric payload produces a 16th check digit that renders the
 *       full PAN Luhn-valid—preventing BIN-spoofing or collision attacks.
 *   <li>Any deviation from the strict 15-digit numeric format triggers an
 *       {@link IllegalArgumentException}, closing the door on injection-style
 *       vulnerabilities or datastore corruption.
 * </ul>
 *
 * <p>Thread-safety: Tests are stateless and execute concurrently via Jqwik’s
 * built-in parallel scheduler, aligning with the platform’s high-volume
 * card-provisioning SLAs.
 *
 * @author Madavan Babu
 * @since 2026
 */
class LuhnAlgorithmPropertyTest {

    /**
     * Mathematical invariant: For every 15-digit numeric payload the algorithm
     * MUST produce a check digit that makes the resultant 16-digit PAN pass
     * the Luhn test.
     *
     * <p>This property is the cornerstone of PAN integrity within the oPayque
     * ecosystem; any regression would allow invalid cards to enter the
     * encrypted store, undermining fraud-scoring models and violating
     * PCI-DSS requirement 3.2.
     *
     * @param payload 15-digit numeric string supplied by Jqwik’s random generator.
     */
    @Property(tries = 1000)
    void luhnCheckDigitInvariant(
            @ForAll @StringLength(value = 15) @NumericChars String payload
    ) {
        // 1. Calculate the check digit dynamically
        int checkDigit = LuhnAlgorithm.calculateCheckDigit(payload);

        // 2. Construct the full PAN
        String candidatePan = payload + checkDigit;

        // 3. Assert the Invariant: The result must ALWAYS be valid
        assertThat(LuhnAlgorithm.isValid(candidatePan))
                .as("Payload %s produced check digit %d, but result %s was invalid", payload, checkDigit, candidatePan)
                .isTrue();
    }

    /**
     * Fuzzes the boundary by supplying arbitrary strings whose length ≠ 15,
     * asserting that the engine fails fast with {@link IllegalArgumentException}.
     *
     * <p>This guards against downstream cryptographic or persistence layers
     * receiving malformed data that could trigger silent corruption or
     * duplicate PAN fingerprints.
     *
     * @param arbitraryString any string whose length is guaranteed ≠ 15.
     */
    @Property(tries = 500)
    void shouldRejectInvalidLengths(@ForAll String arbitraryString) {
        // Filter: We only want strings that are NOT length 15
        Assume.that(arbitraryString.length() != 15);

        assertThatThrownBy(() -> LuhnAlgorithm.calculateCheckDigit(arbitraryString))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Ensures that 15-character strings containing non-digit symbols are
     * immediately rejected, closing attack vectors such as SQL-injection
     * payloads or encoded control characters that could bypass sanitizers.
     *
     * @param nonNumeric 15-character string guaranteed to contain ≥ 1 non-digit.
     */
    @Property(tries = 500)
    void shouldRejectNonNumeric(@ForAll @StringLength(value = 15) String nonNumeric) {
        // Filter: Must contain at least one non-digit
        Assume.that(!nonNumeric.matches("\\d+"));

        assertThatThrownBy(() -> LuhnAlgorithm.calculateCheckDigit(nonNumeric))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Explicitly verifies that a {@code null} payload triggers an
     * {@link IllegalArgumentException}, preventing {@link NullPointerException}
     * explosions deeper in the card-provisioning pipeline where PAN data
     * is already encrypted and harder to debug.
     */
    @Example
    void nullSafetyCheck() {
        assertThatThrownBy(() -> LuhnAlgorithm.calculateCheckDigit(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}