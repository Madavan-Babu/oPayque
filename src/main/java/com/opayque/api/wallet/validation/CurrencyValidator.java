package com.opayque.api.wallet.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.Currency;
import java.util.Set;
import java.util.stream.Collectors;

/// Multi-Currency Account Management - ISO 4217 Validator Implementation.
///
/// Provides the concrete logic for validating currency strings against the
/// Java [Currency] registry. This implementation ensures that only globally
/// recognized ISO 4217 codes are permitted during account provisioning.
///
/// Complexity: O(1) for lookup after static initialization.
public class CurrencyValidator implements ConstraintValidator<ValidCurrency, String> {

    /// Static Cache: Pre-compiles all available ISO 4217 currency codes into an
    /// unmodifiable Set to optimize lookup performance during high-concurrency
    /// request processing.
    private static final Set<String> ISO_CODES = Currency.getAvailableCurrencies()
            .stream()
            .map(Currency::getCurrencyCode)
            .collect(Collectors.toUnmodifiableSet());

    /// Evaluates the validity of a given currency code string.
    ///
    /// Logic Breakdown:
    /// 1. Null/Empty Check: Immediately fails validation to prevent downstream
    ///    processing of malformed identity data.
    /// 2. Exact Match: Performs a case-sensitive check against the ISO_CODES registry
    ///    to adhere to the strict requirements of Story 2.2.
    ///
    /// @param value The currency code string (e.g., "USD", "EUR").
    /// @param context The validation context.
    /// @return true if the code is a valid ISO 4217 identifier; false otherwise.
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        // Defensive Check: Sanitization against null or blank inputs.
        if (value == null || value.trim().isEmpty()) {
            return false;
        }

        // Operational Check: Case-sensitive membership verification.
        // Example: "usd" -> False, "USD" -> True.
        return ISO_CODES.contains(value);
    }
}