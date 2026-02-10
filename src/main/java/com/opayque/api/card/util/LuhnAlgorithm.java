package com.opayque.api.card.util;

/**
 * Stateless utility implementing the Luhn (Mod-10) checksum algorithm for PCI-DSS compliant
 * Primary Account Number (PAN) validation and check-digit generation within the oPayque
 * neobank card issuance platform.
 *
 * <p>The algorithm is mandated by ISO/IEC 7812-1 for card-not-present (CNP) FinTech flows
 * and serves as a first-line defense against accidental PAN typos, BIN attack payloads, and
 * chargeback disputes. It is intentionally lightweight (no external dependencies) to support
 * high-velocity card-factory microservices while maintaining deterministic behavior across
 * JVM versions for regulatory reproducibility (PSD2 RTS).
 *
 * <p>Security considerations:
 * <ul>
 *   <li>Performs input sanitization to reject non-numeric payloads (OWASP ASVS 5.3).
 *   <li>Stateless design ensures thread-safe usage within Spring-managed transactions.
 *   <li>Minimum PAN length enforced at 13 to align with Visa, Mastercard, and UnionPay IIN ranges.
 * </ul>
 *
 * @author Madavan Babu
 * @since 2026
 */
public final class LuhnAlgorithm {

    // Private constructor to prevent instantiation
    private LuhnAlgorithm() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * Validates a raw PAN string against the Luhn checksum to mitigate fraudulent card
     * provisioning and reduce chargeback ratios in card-not-present (CNP) channels.
     *
     * <p>The method normalizes the input by rejecting whitespace and enforces a minimum
     * length of 13 digits to comply with major card scheme IIN ranges (Visa 4*, Mastercard 5*).
     * Returns {@code false} for any non-numeric payload, null input, or PANs shorter than
     * 13 digits, aligning with PCI-DSS requirement 3.2 for truncation and masking.
     *
     * @param pan Raw Primary Account Number (may contain spaces); never persisted in plaintext.
     * @return {@code true} if the PAN satisfies the Mod-10 checksum and is syntactically
     *         eligible for tokenization; {@code false} otherwise.
     */
    public static boolean isValid(String pan) {
        if (pan == null || pan.length() < 13 || !pan.matches("\\d+")) {
            return false;
        }

        int sum = 0;
        boolean doubleDigit = false;

        // Loop from right to left
        for (int i = pan.length() - 1; i >= 0; i--) {
            int digit = Character.getNumericValue(pan.charAt(i));

            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9; // Same as adding the digits of the product (e.g., 18 -> 1+8=9)
                }
            }

            sum += digit;
            doubleDigit = !doubleDigit; // Flip the flag
        }

        return (sum % 10 == 0);
    }

    /**
     * Computes the 16th check digit for a 15-digit BIN/IIN + account-number payload during
     * virtual card manufacturing, ensuring downstream scheme compliance and BIN table
     * uniqueness within the oPayque ledger.
     *
     * <p>The method applies the ISO/IEC 7812-1 Annex B algorithm: double every second digit
     * starting from the left, subtract 9 if product > 9, and derive the digit that makes
     * the aggregate sum a multiple of 10. This guarantees that the resulting 16-digit PAN
     * will pass Luhn validation when presented to merchants or 3-D Secure access control
     * servers (ACS).
     *
     * @param first15Digits Exactly 15 numeric characters comprising IIN + individual account
     *                      identifier; must not be null or contain non-digits.
     * @return Single numeric check digit (0–9) to append, ensuring PAN integrity across
     *         issuer switches and token vault migrations.
     * @throws IllegalArgumentException if the payload is null, non-numeric, or length ≠ 15.
     */
    public static int calculateCheckDigit(String first15Digits) {
        if (first15Digits == null || first15Digits.length() != 15 || !first15Digits.matches("\\d+")) {
            throw new IllegalArgumentException("Input must be exactly 15 digits.");
        }

        int sum = 0;
        boolean doubleDigit = true; // For the 16th digit (index 15), index 14 is the "second from right", so we start doubling.

        // Loop from left to right (Indices 0 to 14)
        for (int i = 0; i < 15; i++) {
            int digit = Character.getNumericValue(first15Digits.charAt(i));

            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }

            sum += digit;
            doubleDigit = !doubleDigit;
        }

        // The check digit must make the total sum a multiple of 10
        int remainder = sum % 10;
        return (remainder == 0) ? 0 : (10 - remainder);
    }
}