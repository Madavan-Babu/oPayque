package com.opayque.api.transactions.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import net.jqwik.api.*;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/// **Story 3.3: DTO Fuzzing & Chaos Testing**.
///
/// Uses Jqwik to bombard the API Contract (`TransferRequest`) with infinite random data.
///
/// **Goal:** Prove that the Input Validation layer is "Panic Proof."
/// **Invariant:** No matter what garbage enters (SQL Injection, Emojis, 1GB Strings),
/// the validator MUST return a structured error report (ConstraintViolations)
/// and MUST NEVER throw an unchecked RuntimeException (500 Error).
class TransferDtoPropertyTest {

    // Initialize statically to ensure it is ready for Jqwik
    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    /// **Invariant: The "No Crash" Guarantee.**
    ///
    /// Generates strictly "Chaos" strings for all fields (SQL injection, XSS payloads,
    /// non-printable control characters, etc.) and asserts that the validation logic
    /// handles them gracefully.
    @Property(tries = 500)
    void validationMustNeverCrashOnChaosInputs(
            @ForAll("chaosStrings") String email,
            @ForAll("chaosStrings") String amount,
            @ForAll("chaosStrings") String currency
    ) {
        // 1. Construct DTO with pure garbage
        TransferRequest request = new TransferRequest(email, amount, currency);

        // 2. Act: Validate
        Set<ConstraintViolation<TransferRequest>> violations;
        try {
            violations = validator.validate(request);
        } catch (Exception e) {
            throw new AssertionError("Validator CRASHED on input: " + request, e);
        }

        // 3. Assert: We expect violations for garbage.
        assertThat(violations).isNotNull();
    }

    /// **Invariant: The "Financial Format" Strictness.**
    ///
    /// Proves that ONLY structurally valid numbers pass the "Amount" check.
    /// Everything else (text, mixed chars, currency symbols) must be rejected.
    @Property
    void nonNumericAmountsMustAlwaysBeRejected(@ForAll("alphaChars") String amount) {
        TransferRequest request = new TransferRequest("bob@opayque.com", amount, "USD");

        Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                // FIX: Match the actual DTO custom message
                .anyMatch(msg -> msg.contains("Amount must be a positive number"));
    }

    /// **Invariant: The "Currency Code" Standard.**
    ///
    /// Proves that we strictly enforce the 3-letter ISO format.
    /// Emojis 💰, 2-letter codes, or 4-letter codes must be rejected.
    @Property
    void invalidCurrencyCodesMustBeRejected(@ForAll("invalidCurrencies") String currency) {
        TransferRequest request = new TransferRequest("bob@opayque.com", "100.00", currency);

        Set<ConstraintViolation<TransferRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(ConstraintViolation::getMessage)
                // FIX: Match the actual DTO custom message
                .anyMatch(msg -> msg.contains("Currency must be a 3-letter ISO code"));
    }

    // --- GENERATORS (The Chaos Factory) ---

    @Provide
    Arbitrary<String> chaosStrings() {
        return Arbitraries.oneOf(
                Arbitraries.strings()
                        .withCharRange(Character.MIN_VALUE, Character.MAX_VALUE)
                        .ofMinLength(0)
                        .ofMaxLength(1000),
                Arbitraries.just(null)
        );
    }

    @Provide
    Arbitrary<String> alphaChars() {
        return Arbitraries.strings().alpha().ofMinLength(1);
    }

    @Provide
    Arbitrary<String> invalidCurrencies() {
        return Arbitraries.oneOf(
                Arbitraries.of("💰", "💵", "EUR€"),
                Arbitraries.strings().alpha().ofLength(2),
                Arbitraries.strings().alpha().ofLength(4),
                Arbitraries.strings().numeric().ofLength(3)
        );
    }
}