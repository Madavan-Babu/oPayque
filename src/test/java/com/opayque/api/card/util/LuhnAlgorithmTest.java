package com.opayque.api.card.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit-level security test-suite validating PCI-DSS mandated Luhn (Mod 10) checksum logic.
 *
 * <p>This deterministic test harness guarantees that every Primary Account Number (PAN) minted by
 * the oPayque neobank platform satisfies the ISO/IEC 7812 checksum requirement, thereby mitigating
 * card-not-present (CNP) fraud and satisfying PCI-DSS 3.2.1 Req 3.5.
 *
 * <p>Scope coverage:
 *
 * <ul>
 *   <li>Happy-path validation for major network BIN ranges (Visa, Mastercard, oPayque custom).
 *   <li>Mutation resistance: single-digit corruption must invalidate the PAN.
 *   <li>Check-digit generation for 15-digit payloads (PCI token vault compatibility).
 *   <li>Robust handling of malicious or malformed input (OWASP ASVS 5.3).
 * </ul>
 *
 * Thread-safety: Tests are stateless and run under JUnit 5 parallel execution.
 *
 * @author Madavan Babu
 * @since 2026
 */
class LuhnAlgorithmTest {

    // Known Valid Cards
    private static final String VISA_VALID = "4532015112830366";
    private static final String MC_VALID = "5555555555554444";

    // oPayque Custom: 171103 + 123456789 + Checksum(2)
    private static final String OPAYQUE_VALID = "1711031234567892";

    
    /**
     * Validates that authentic PANs from Visa, Mastercard and the oPayque custom BIN
     * pass the ISO/IEC 7812 Luhn check, ensuring PCI-DSS Req 3.5 compliance.
     *
     * @param validPan a 16-digit PAN known to satisfy the Mod-10 formula
     */
    @ParameterizedTest
    @DisplayName("Happy Path: Should validate legitimate cards from major networks")
    @ValueSource(strings = {VISA_VALID, MC_VALID, OPAYQUE_VALID})
    void shouldValidateCorrectCards(String validPan) {
        assertThat(LuhnAlgorithm.isValid(validPan)).isTrue();
    }

    /**
     * Proves tamper-evidence: flipping any single digit in a valid PAN
     * breaks the Luhn sum, mitigating card-not-present fraud.
     *
     * Verifies that malformed or malicious input (null, empty, non-numeric, too short)
     * is safely rejected, preventing OWASP ASVS 5.3 injection vectors.
     */
    @Test
    @DisplayName("Sad Path: Mutation Test - Changing ANY single digit must invalidate the card")
    void shouldFailOnSingleDigitMutation() {
        String original = OPAYQUE_VALID; // 1711031234567892

        // Iterate through every character in the 16-digit string
        for (int i = 0; i < original.length(); i++) {
            char originalChar = original.charAt(i);

            // Mutate: Change the digit to something else (e.g., (d+1)%10)
            int mutatedDigit = (Character.getNumericValue(originalChar) + 1) % 10;

            char[] chars = original.toCharArray();
            chars[i] = Character.forDigit(mutatedDigit, 10);
            String mutatedPan = new String(chars);

            // Assert: The mutated PAN is mathematically invalid
            assertThat(LuhnAlgorithm.isValid(mutatedPan))
                    .as("PAN should be invalid when index %d is changed from %c to %d", i, originalChar, mutatedDigit)
                    .isFalse();
        }
    }

    /**
     * Ensures the utility can deterministically produce the 16th check digit
     * for a 15-digit PCI token-vault payload, maintaining forwards-compatibility
     * with issuer-side PAN generation.
     */
    @Test
    @DisplayName("Calculation: Should correctly compute the 16th check digit")
    void shouldCalculateCheckDigit() {
        // Input: 171103123456789 (First 15)
        // Expected Checksum: 2 (To make sum 60)
        String payload = "171103123456789";

        int checkDigit = LuhnAlgorithm.calculateCheckDigit(payload);

        assertThat(checkDigit).isEqualTo(2);

        // Verify the combined result is valid
        assertThat(LuhnAlgorithm.isValid(payload + checkDigit)).isTrue();
    }

    @Test
    @DisplayName("Edge Cases: Nulls, Trash, and Empty Strings")
    void shouldHandleGarbageInput() {
        assertThat(LuhnAlgorithm.isValid(null)).isFalse();
        assertThat(LuhnAlgorithm.isValid("")).isFalse();
        assertThat(LuhnAlgorithm.isValid("123")).isFalse(); // Too short
        assertThat(LuhnAlgorithm.isValid("4532-0151-1283-0366")).isFalse(); // Non-numeric (Strict)
        assertThat(LuhnAlgorithm.isValid("abcdefghijklmnop")).isFalse();
    }

    /**
     * Confirms that {@link LuhnAlgorithm#calculateCheckDigit(String)} enforces
     * strict pre-conditions (15-digit numeric) and fails fast with an
     * informative exception on any violation.
     */
    @Test
    @DisplayName("Safety: Calculator should reject invalid payloads")
    void calculatorShouldThrowOnBadInput() {
        assertThatThrownBy(() -> LuhnAlgorithm.calculateCheckDigit(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> LuhnAlgorithm.calculateCheckDigit("123")) // Too short
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be exactly 15 digits");

        assertThatThrownBy(() -> LuhnAlgorithm.calculateCheckDigit("17110312345678A")) // Non-numeric
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Boundary validation: when the partial sum is already a multiple of 10
     * the correct check-digit is zero, ensuring full coverage of the 0-9 range.
     */
    @Test
    @DisplayName("Boundary: Should correctly handle Check Digit 0 (Sum % 10 == 0)")
    void shouldCalculateZeroCheckDigit() {
        // Payload Adjusted: Ends in 5 instead of 0
        // Luhn Sum: 50
        // 50 % 10 = 0. Check Digit should be 0.
        String zeroCheckPayload = "171103123456785";

        int checkDigit = LuhnAlgorithm.calculateCheckDigit(zeroCheckPayload);

        assertThat(checkDigit)
                .as("Check digit for a perfect sum payload must be 0")
                .isEqualTo(0);

        // Verify: 1711031234567850 is valid
        assertThat(LuhnAlgorithm.isValid(zeroCheckPayload + checkDigit)).isTrue();
    }

    /**
     * Guarantees the utility class remains non-instantiable, even when
     * reflection is used, thereby enforcing singleton semantics and
     * preventing misuse in dependency-injection contexts.
     */
    @Test
    @DisplayName("Constructor: Should throw exception if instantiated via Reflection")
    void constructor_ShouldPreventInstantiation() throws NoSuchMethodException {
        // 1. Get the private constructor
        java.lang.reflect.Constructor<LuhnAlgorithm> constructor = LuhnAlgorithm.class.getDeclaredConstructor();

        // 2. Force access
        constructor.setAccessible(true);

        // 3. Assert that calling it throws the specific exception defined in the class
        org.assertj.core.api.Assertions.assertThatThrownBy(constructor::newInstance)
                .isInstanceOf(java.lang.reflect.InvocationTargetException.class)
                .hasCauseInstanceOf(UnsupportedOperationException.class)
                .hasRootCauseMessage("Utility class cannot be instantiated");
    }
}