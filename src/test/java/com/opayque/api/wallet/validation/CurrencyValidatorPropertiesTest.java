package com.opayque.api.wallet.validation;

import net.jqwik.api.*;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;

import java.util.Currency;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/// Multi-Currency Account Management - Currency Validation Property Audit.
///
/// This suite utilizes the JQwik engine to execute property-based testing against the
/// [CurrencyValidator]. Unlike traditional unit tests that use static examples,
/// this audit performs exhaustive fuzzing and consistency checks to ensure the
/// validation logic satisfies ISO 4217 constraints under thousands of random inputs.
class CurrencyValidatorPropertiesTest {

    /// The concrete validator instance under test.
    private final CurrencyValidator validator = new CurrencyValidator();

    /// Source of Truth: Pre-compiled registry of all valid Java ISO 4217 currency codes.
    /// Utilized for generating valid inputs and asserting the failure of invalid candidates.
    private static final Set<String> VALID_ISO_CODES = Currency.getAvailableCurrencies()
            .stream()
            .map(Currency::getCurrencyCode)
            .collect(Collectors.toSet());

    /// Property: Fuzzing Audit.
    ///
    /// Ensures that any arbitrary string that is not a recognized ISO 4217 code is
    /// strictly rejected by the validator. This guards against data corruption
    /// and malformed currency inputs in the ledger.
    ///
    /// @param candidate A randomly generated alphanumeric string (1-10 characters).
    @Property
    @Label("Fuzzing: Any string that is NOT a valid ISO 4217 code must be rejected by the Validator")
    void invalidCurrenciesShouldFail(
            @ForAll @AlphaChars @StringLength(min = 1, max = 10) String candidate) {

        // Assumption: Ensure the fuzzer hasn't accidentally generated a valid code.
        Assume.that(!VALID_ISO_CODES.contains(candidate));

        // Act: Evaluate the candidate against the ISO gatekeeper.
        boolean isValid = validator.isValid(candidate, null);

        // Assert: Rejection is mandatory for non-ISO strings.
        assertThat(isValid).isFalse();
    }

    /// Property: Consistency Audit.
    ///
    /// Validates that every currency code supported by the underlying Java runtime
    /// is successfully recognized by our validator. This ensures zero "False Negatives"
    /// for valid global currencies.
    ///
    /// @param currencyCode A valid ISO 4217 code provided by (validCurrencies).
    @Property
    @Label("Consistency: All known Java ISO codes must pass the Validator")
    void validIsoCodesShouldPass(@ForAll("validCurrencies") String currencyCode) {
        // Act: Validate a known-good ISO code.
        boolean isValid = validator.isValid(currencyCode, null);

        // Assert: Recognition is mandatory for official ISO 4217 identifiers.
        assertThat(isValid).isTrue();
    }

    /// Property: Robustness Audit.
    ///
    /// Confirms that null, empty, or whitespace-only inputs are consistently rejected,
    /// satisfying the strict non-null requirements for financial account metadata.
    ///
    /// @param invalidInput A null or blank string provided by (emptyStrings).
    @Property
    @Label("Robustness: Null or Empty inputs must be rejected")
    void nullOrEmptyShouldFail(@ForAll("emptyStrings") String invalidInput) {
        boolean isValid = validator.isValid(invalidInput, null);
        assertThat(isValid).isFalse();
    }

    /// Provider: Generates valid ISO 4217 identifiers from the system registry.
    @Provide
    Arbitrary<String> validCurrencies() {
        return Arbitraries.of(VALID_ISO_CODES);
    }

    /// Provider: Generates malformed boundary inputs (Null, Empty, Blank).
    @Provide
    Arbitrary<String> emptyStrings() {
        return Arbitraries.of(null, "", "   ");
    }
}