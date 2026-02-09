
package com.opayque.api.infrastructure.encryption;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;


/**
 * PCI-DSS Lite Encryption Engine – AES-256 Column-Level Cryptography.
 *
 * <p>
 * A JPA {@link jakarta.persistence.AttributeConverter} implementation that
 * provides transparent encryption and decryption of sensitive columns
 * (e.g., PAN, CVV) at the persistence layer. All cryptographic operations
 * are performed with AES-256 using a key derived from a deployment-specific
 * passphrase injected via {@code opayque.security.encryption-key}.
 * </p>
 *
 * <p>
 * Cipher-text is Base64-encoded before storage to guarantee VARCHAR
 * compatibility and to mitigate character-encoding issues across heterogeneous
 * databases. Null values are handled gracefully and remain null throughout the
 * conversion lifecycle.
 * </p>
 *
 * <p>
 * <b>Key Derivation:</b> SHA-256 is used as a stable Key-Derivation Function
 * (KDF) to produce a 256-bit key regardless of passphrase length, ensuring
 * compliance with AES key-length requirements.
 * </p>
 *
 * <p>
 * <b>Thread Safety:</b> Instances are stateless post-construction and are
 * safe for concurrent use by the JPA provider.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 */
@Slf4j
@Component
@Converter
public class AttributeEncryptor implements AttributeConverter<String, String> {

    private static final String ALGORITHM = "AES";
    private final Key key;

    /**
     * Constructs an {@code AttributeEncryptor} with a passphrase-derived AES-256 key.
     *
     * <p>
     * The supplied secret is hashed once with SHA-256 to produce a 32-byte key,
     * satisfying AES-256 key-length mandates. Any failure during key derivation
     * is logged as a critical event and translated into an
     * {@link IllegalStateException}, preventing the Spring context from starting
     * in an insecure state.
     * </p>
     *
     * @param secret the deployment-specific passphrase injected from
     *               {@code opayque.security.encryption-key}; must not be {@code null}
     *               or empty
     * @throws IllegalStateException if SHA-256 is not available in the runtime
     */
    public AttributeEncryptor(@Value("${opayque.security.encryption-key}") String secret) {
        try {
            // 1. Key Derivation Function (KDF)
            // We force the input string into a 256-bit (32-byte) hash.
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] keyBytes = sha.digest(secret.getBytes(StandardCharsets.UTF_8));

            // 2. Create the AES KeySpec
            this.key = new SecretKeySpec(keyBytes, ALGORITHM);

        } catch (NoSuchAlgorithmException e) {
            log.error("CRITICAL: SHA-256 algorithm not found. Encryption engine failed.");
            throw new IllegalStateException("Encryption setup failed", e);
        }
    }

    /**
     * Encrypts a plaintext value before JPA writes it to the database.
     *
     * <p>
     * Uses AES in ECB mode with PKCS5 padding and Base64-encodes the resulting
     * ciphertext to produce a VARCHAR-compatible string. This method is
     * invoked automatically by the JPA provider whenever an entity attribute
     * annotated with {@code @Convert} is flushed.
     * </p>
     *
     * @param attribute the plaintext value from the entity; may be {@code null}
     * @return Base64-encoded ciphertext, or {@code null} when input is {@code null}
     * @throws IllegalStateException if encryption fails (e.g., invalid key, corrupted
     *                             provider)
     */
    @Override
    public String convertToDatabaseColumn(String attribute) {
        // Handle nulls gracefully (e.g., nullable columns)
        if (attribute == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, key);

            // Encrypt and then Encode to Base64 (so it fits in a VARCHAR column)
            byte[] encryptedBytes = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encryptedBytes);

        } catch (Exception e) {
            log.error("Encryption Failure. Check Key configuration.");
            throw new IllegalStateException("Error encrypting sensitive data", e);
        }
    }

    /**
     * Decrypts a ciphertext value after JPA reads it from the database.
     *
     * <p>
     * Expects a Base64-encoded ciphertext produced by
     * {@link #convertToDatabaseColumn(String)}. The method decodes the payload
     * and decrypts it with AES-256, returning the original plaintext. This
     * operation is invoked automatically by the JPA provider during
     * entity hydration.
     * </p>
     *
     * @param dbData the Base64-encoded ciphertext stored in the column;
     *               may be {@code null}
     * @return the decrypted plaintext, or {@code null} when input is {@code null}
     * @throws IllegalStateException if decryption fails (e.g., key mismatch, data
     *                             corruption)
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        // Handle nulls gracefully
        if (dbData == null) {
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, key);

            // Decode Base64 first, then Decrypt
            byte[] decodedBytes = Base64.getDecoder().decode(dbData);
            return new String(cipher.doFinal(decodedBytes), StandardCharsets.UTF_8);

        } catch (Exception e) {
            log.error("Decryption Failure. Data corruption or Key mismatch.");
            throw new IllegalStateException("Error decrypting sensitive data", e);
        }
    }
}
