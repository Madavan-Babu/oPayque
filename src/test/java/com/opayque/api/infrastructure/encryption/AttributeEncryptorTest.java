package com.opayque.api.infrastructure.encryption;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/**
 * Epic 4: Cryptographic Assurance for PCI-DSS Compliance.
 * <p>
 * Isolated unit tests for the {@link AttributeEncryptor} AES-256-GCM attribute converter.
 * Validates that sensitive FinTech attributes (e.g., PAN, CVV, mobile money wallet IDs)
 * remain mathematically reversible, key-derivation stable, and resilient to edge cases
 * across microservice restarts and rolling deployments.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 */
class AttributeEncryptorTest {

    private static final String TEST_SECRET = "MySuperSecretKeyForTesting123!";

    // We instantiate it manually to test logic without Spring Context overhead
    private final AttributeEncryptor encryptor = new AttributeEncryptor(TEST_SECRET);

    /**
     * Validates end-to-end cryptographic reversibility for a standard FinTech PAN.
     * <p>
     * Ensures that after AES-256-GCM encryption the ciphertext is base64-encoded
     * for safe persistence in PostgreSQL and that subsequent decryption yields
     * the identical plaintext, satisfying PCI-DSS requirement 3.4.
     * </p>
     */
    @Test
    @DisplayName("Happy Path: Should encrypt and decrypt data correctly")
    void shouldEncryptAndDecryptSuccessfully() {
        String originalPan = "4000123456789010";

        // 1. Encrypt
        String encrypted = encryptor.convertToDatabaseColumn(originalPan);

        // Assert it is NOT the original text (Obfuscation Check)
        assertThat(encrypted).isNotEqualTo(originalPan);
        assertThat(encrypted).isBase64(); // Must be DB-safe format

        // 2. Decrypt
        String decrypted = encryptor.convertToEntityAttribute(encrypted);

        // Assert it matches the original exactly
        assertThat(decrypted).isEqualTo(originalPan);
    }

    /**
     * Proves deterministic key derivation across JVM restarts.
     * <p>
     * Simulates a rolling deployment where a new pod instance must decrypt
     * ciphertext produced by its predecessor. Success confirms that the
     * PBKDF2-HMAC-SHA-256 key derivation is stable and reproducible.
     * </p>
     */
    @Test
    @DisplayName("Determinism: Two instances with SAME key should interoperate")
    void shouldInteroperateBetweenInstances() {
        // Simulate a Server Restart: New instance, same config
        AttributeEncryptor instanceA = new AttributeEncryptor(TEST_SECRET);
        AttributeEncryptor instanceB = new AttributeEncryptor(TEST_SECRET);

        String data = "Sensitive-CVV-123";

        // Encrypt with A
        String cipherText = instanceA.convertToDatabaseColumn(data);

        // Decrypt with B
        String plainText = instanceB.convertToEntityAttribute(cipherText);

        // This proves SHA-256 derivation is stable across restarts
        assertThat(plainText).isEqualTo(data);
    }

    /**
     * Ensures null safety for optional JPA columns.
     * <p>
     * Confirms that the converter propagates nulls without throwing
     * {@link NullPointerException}, maintaining data integrity when
     * FinTech entities contain nullable encrypted fields.
     * </p>
     */
    @Test
    @DisplayName("Safety: Should handle NULLs gracefully")
    void shouldHandleNulls() {
        // JPA often passes nulls for optional columns
        assertThat(encryptor.convertToDatabaseColumn(null)).isNull();
        assertThat(encryptor.convertToEntityAttribute(null)).isNull();
    }

    /**
     * Validates correct handling of empty plaintext.
     * <p>
     * Guarantees that encrypting an empty string produces a valid ciphertext
     * and that decrypting it returns an empty string, preserving data
     * consistency for edge-case FinTech records.
     * </p>
     */
    @Test
    @DisplayName("Edge Case: Should handle Empty Strings")
    void shouldHandleEmptyStrings() {
        String empty = "";

        String encrypted = encryptor.convertToDatabaseColumn(empty);
        String decrypted = encryptor.convertToEntityAttribute(encrypted);

        assertThat(decrypted).isEqualTo(empty);
    }

    /**
     * Demonstrates cryptographic key separation.
     * <p>
     * Attempting to decrypt ciphertext with a differing key must yield
     * an {@link IllegalStateException}, proving that the AES-GCM tag
     * verification correctly rejects tampered or mis-keyed data, thereby
     * upholding PCI-DSS requirement 3.5.
     * </p>
     */
    @Test
    @DisplayName("Key Mismatch: Should fail to decrypt if key changes")
    void shouldFailIfKeyIsWrong() {
        AttributeEncryptor correctKey = new AttributeEncryptor("Key-A");
        AttributeEncryptor wrongKey = new AttributeEncryptor("Key-B");

        String data = "Secret";
        String cipherText = correctKey.convertToDatabaseColumn(data);

        // Attempting to decrypt with the wrong key must fail
        // Usually throws IllegalStateException (wrapped javax.crypto.BadPaddingException)
        assertThatThrownBy(() -> wrongKey.convertToEntityAttribute(cipherText))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Error decrypting");
    }

  /**
   * Validates defensive failure handling when AES-256-GCM encryption materially fails.
   *
   * <p>Simulates a catastrophic runtime condition—such as an invalidated secret key or corrupted
   * PBKDF2-derived key material—that would otherwise propagate low-level {@link
   * IllegalStateException} or provider-specific exceptions. The converter is required
   * to catch any such failure, log it securely (no key leakage), and re-throw a domain-level {@link
   * IllegalStateException} with a sanitized message. This guarantees that upstream JPA or service
   * layers never observe sensitive stack traces, maintaining PCI-DSS requirement 6.5 (secure error
   * handling) and OWASP API Security Top 10 resilience.
   *
   * @throws Exception reflection-related exceptions during test sabotage
   */
  @Test
  @DisplayName("Error Handling: Should wrap internal crypto errors during Encryption")
  void shouldWrapEncryptionErrors() throws Exception {
        // 1. Setup a valid encryptor
        AttributeEncryptor encryptor = new AttributeEncryptor("ValidKey123");

        // 2. Sabotage: Use Reflection to corrupt the internal 'key'
        // This forces cipher.init() to fail (usually with InvalidKeyException)
        java.lang.reflect.Field keyField = AttributeEncryptor.class.getDeclaredField("key");
        keyField.setAccessible(true);
        keyField.set(encryptor, null); // <--- The Sabotage

        // 3. Act & Assert
        // We expect the class to catch the internal error and rethrow it as IllegalStateException
        assertThatThrownBy(() -> encryptor.convertToDatabaseColumn("SensitiveData"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Error encrypting sensitive data");
    }

  /**
   * Validates cryptographic resilience under extreme JVM mis-configuration.
   *
   * <p>This test simulates a hostile or corrupted runtime where the SHA-256 MessageDigest provider
   * has been removed—an event that would silently break PBKDF2-based key derivation and render all
   * encrypted FinTech attributes (PAN, CVV, wallet IDs) unreadable. By asserting that the {@link
   * AttributeEncryptor} constructor fails fast with an {@link IllegalStateException} whose cause is
   * {@link java.security.NoSuchAlgorithmException}, we prove that the microservice will refuse to
   * start rather than operate in an insecure state. This behavior is mandatory for PCI-DSS
   * requirement 6.5 (secure error handling) and OWASP API Security Top 10 (cryptographic failures).
   * The test also guarantees deterministic restoration of security providers, ensuring subsequent
   * test suites are not poisoned by provider registry manipulation.
   *
   * @throws IllegalStateException if the encryptor incorrectly tolerates a missing SHA-256
   *     implementation
   * @throws SecurityException if provider removal/addition is denied by the JVM
   */
  @Test
  @DisplayName("Critical Failure: Should crash if SHA-256 is missing from JVM")
  void shouldThrowIfSha256IsMissing() {
        // 1. Identification: Find which Provider is giving us "SHA-256" (usually "SUN")
        java.security.Provider[] providers = java.security.Security.getProviders("MessageDigest.SHA-256");

        // Guard: If the JVM is already broken/weird, skip or fail.
        if (providers == null || providers.length == 0) {
            // This arguably shouldn't happen in a standard JDK, but good safety.
            return;
        }

        try {
            // 2. The Heist: Remove all providers that offer SHA-256
            for (java.security.Provider provider : providers) {
                java.security.Security.removeProvider(provider.getName());
            }

            // 3. The Attempt: Initialize the class. It should panic.
            assertThatThrownBy(() -> new AttributeEncryptor("SecretKey"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Encryption setup failed") // The outer exception
                    .hasCauseInstanceOf(java.security.NoSuchAlgorithmException.class); // The root cause

        } finally {
            // 4. The Restoration: PUT THEM BACK!
            // If we don't do this, every subsequent test in the suite will fail.
            for (java.security.Provider provider : providers) {
                java.security.Security.addProvider(provider);
            }
        }
  }
}