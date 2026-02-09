package com.opayque.api.infrastructure.encryption;

import net.jqwik.api.*;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// **Story 4.1: Encryption Engine Chaos Testing**
///
/// Uses Property-Based Testing (Jqwik) to fuzz the Encryption Engine.
/// Validates the Invariant: decrypt(encrypt(input)) == input
/// regardless of the input's content (Unicode, Emojis, Control Chars).
class AttributeEncryptorPropertyTest {

    // We use a fixed key for the property tests to ensure consistency
    private final AttributeEncryptor encryptor = new AttributeEncryptor("PropertyTestKey-1234567890");

    /// The Golden Rule: Round Trip Integrity.
    ///
    /// Jqwik will generate 1000+ random strings, including:
    /// - Empty strings
    /// - Standard ASCII
    /// - Chinese/Japanese/Korean characters (Multi-byte)
    /// - Emojis (4-byte Unicode)
    /// - SQL Injection payloads
    /// - Control characters (\n, \t, \0)
    @Property(tries = 1000)
    void invariantRoundTrip(@ForAll String input) {
        // 1. Encrypt (The Chaos)
        String cipherText = encryptor.convertToDatabaseColumn(input);

        // 2. Decrypt (The Order)
        String plainText = encryptor.convertToEntityAttribute(cipherText);

        // 3. Assert (The Truth)
        assertThat(plainText).isEqualTo(input);
    }

    /// The "Stress" Test: Massive Payloads.
    ///
    /// Explicitly tests large inputs (up to 1MB) to ensure:
    /// 1. We don't hit BufferOverflows.
    /// 2. The Base64 encoder handles large streams correctly without padding errors.
    @Property(tries = 50)
    void massiveStringSupport(@ForAll @StringLength(min = 10000, max = 1000000) String massiveInput) {
        String cipherText = encryptor.convertToDatabaseColumn(massiveInput);
        String plainText = encryptor.convertToEntityAttribute(cipherText);

        assertThat(plainText).isEqualTo(massiveInput);
    }

    /// The "Null" Safety Net.
    ///
    /// Ensures that the generator handles nulls if explicitly passed,
    /// preventing NullPointerExceptions in the engine.
    @Test
    void nullSafetyCheck() {
        assertThat(encryptor.convertToDatabaseColumn(null)).isNull();
        assertThat(encryptor.convertToEntityAttribute(null)).isNull();
    }
}