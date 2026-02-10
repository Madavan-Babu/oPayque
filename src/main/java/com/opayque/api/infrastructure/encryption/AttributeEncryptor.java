package com.opayque.api.infrastructure.encryption;

import com.opayque.api.infrastructure.config.OpayqueSecurityProperties;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;



/**
 * Bank-grade, PCI-DSS compliant encryption engine for safeguarding cardholder data (CHD) and
 * other sensitive attributes within the oPayque neobank ecosystem.
 * <p>
 * Implements an AES-256-GCM based {@link AttributeConverter} with versioned keys, 100 000
 * PBKDF2 iterations, deterministic HMAC blind indexes and full ciphertext integrity verification.
 * All cryptographic material is derived from injected passphrases bound to immutable salt
 * prefixes, guaranteeing deterministic key rotation without re-encryption of historical data.
 * <p>
 * Thread-safe and stateless for JPA persistence operations; lifecycle managed by Spring.
 *
 * @author Madavan Babu
 * @since 2026
 */
@Slf4j
@Component
@Converter
@RequiredArgsConstructor
public class AttributeEncryptor implements AttributeConverter<String, String> {

    private static final String ENCRYPT_ALGO = "AES/GCM/NoPadding";
    private static final String HASH_ALGO = "HmacSHA256";
    private static final String KDF_ALGO = "PBKDF2WithHmacSHA256";

    // NIST Recommendations (2026)
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;
    private static final int KDF_ITERATIONS = 100_000; // Hardened against GPU Brute-force
    private static final int KEY_LENGTH_BIT = 256;
    private static final String SALT_PREFIX = "OPAYQUE_MIASMA_SALT"; // DO NOT CHANGE ONCE CARDS ARE GENERATED!

    // Inject the Properties POJO instead of @Value SpEL
    private final OpayqueSecurityProperties securityProperties;

    private final Map<Byte, SecretKey> keyStore = new ConcurrentHashMap<>();
    private SecretKey hashingKey;
    private byte activeVersion;

    private static AttributeEncryptor INSTANCE;


    /**
     * Post-construction lifecycle hook that validates security configuration and pre-computes
     * all versioned AES keys from the injected {@link OpayqueSecurityProperties}.
     * <p>
     * Fail-fast on missing or inconsistent properties prevents silent runtime crypto failures.
     */
    @PostConstruct
    public void init() {
        log.info("Initializing Fortress Engine... (KDF Iterations: {})", KDF_ITERATIONS);

        // FIX: Access values from the properties object
        if (securityProperties.getActiveVersion() == null) {
            throw new IllegalStateException("opayque.security.active-version is missing");
        }
        this.activeVersion = securityProperties.getActiveVersion().byteValue();

        Map<Integer, String> rawKeys = securityProperties.getKeys();
        if (rawKeys == null || rawKeys.isEmpty()) {
            throw new IllegalStateException("opayque.security.keys is empty or missing");
        }

        for (Map.Entry<Integer, String> entry : rawKeys.entrySet()) {
            byte version = entry.getKey().byteValue();
            String passphrase = entry.getValue();

            String versionSalt = SALT_PREFIX + version;
            byte[] saltBytes = versionSalt.getBytes(StandardCharsets.UTF_8);

            long start = System.currentTimeMillis();
            keyStore.put(version, deriveKey(passphrase, saltBytes));
            long duration = System.currentTimeMillis() - start;

            log.info("Derived Key v{} in {}ms", version, duration);
        }

        if (!keyStore.containsKey(activeVersion)) {
            throw new IllegalStateException("Active Key Version " + activeVersion + " not found!");
        }

        String hashingPassphrase = securityProperties.getHashingKey();
        if (hashingPassphrase == null) {
            throw new IllegalStateException("opayque.security.hashing-key is missing");
        }

        this.hashingKey = new SecretKeySpec(
                hashingPassphrase.getBytes(StandardCharsets.UTF_8), HASH_ALGO
        );

        INSTANCE = this;
    }

    /**
     * Derives a 256-bit AES key from the supplied passphrase and salt using PBKDF2-HMAC-SHA256
     * with 100 000 iterations, meeting OWASP ASVS 5.0 key-stretching requirements.
     *
     * @param password the confidential passphrase
     * @param salt     deterministic, version-bound salt
     * @return strong {@link SecretKey} suitable for AES-GCM encryption
     */
    private SecretKey deriveKey(String password, byte[] salt) {
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(KDF_ALGO);
            PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, KDF_ITERATIONS, KEY_LENGTH_BIT);
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("KDF Failure", e);
        }
    }


    // =========================================================================
    // ENCRYPTION (AES-GCM)
    // =========================================================================
    /**
     * Encrypts a plaintext attribute using the active AES-GCM key.
     * <p>
     * Produces a Base64-encoded payload containing: version ‖ IV ‖ ciphertext ‖ auth-tag.
     * Random IV guarantees semantic security; version byte enables seamless key rotation.
     *
     * @param attribute plaintext value (e.g., PAN, CVV)
     * @return ciphertext string for secure database storage
     */
    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null) return null;
        try {
            byte[] iv = new byte[IV_LENGTH_BYTE];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ENCRYPT_ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);

            SecretKey key = keyStore.get(activeVersion);
            cipher.init(Cipher.ENCRYPT_MODE, key, spec);

            byte[] cipherText = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            ByteBuffer buffer = ByteBuffer.allocate(1 + IV_LENGTH_BYTE + cipherText.length);
            buffer.put(activeVersion);
            buffer.put(iv);
            buffer.put(cipherText);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (IllegalStateException e) {
            // Re-throw configuration errors as-is (don't wrap them)
            throw e;
        } catch (java.security.InvalidKeyException e) {
            log.error("Encryption Key Invalid. Check JCE Policy files or Key generation.");
            throw new IllegalStateException("Encryption Key Error", e);
        } catch (java.security.GeneralSecurityException e) {
            log.error("Crypto Failure during Encryption: {}", e.getMessage());
            throw new IllegalStateException("Encryption System Failure", e);
        } catch (Exception e) {
            log.error("Unexpected Encryption Error", e);
            throw new IllegalStateException("Encryption Failed", e);
        }
    }

    // =========================================================================
    // DECRYPTION (AES-GCM) - Supports ALL Versions (Rotation)
    // =========================================================================
    /**
     * Decrypts a ciphertext produced by {@link #convertToDatabaseColumn(String)}.
     * <p>
     * Supports transparent key rotation by inspecting the embedded version byte and selecting
     * the corresponding decryption key. Integrity is enforced via GCM authentication tag; any
     * tampering results in an {@link javax.crypto.AEADBadTagException}.
     *
     * @param dbData Base64-encoded ciphertext from the database
     * @return original plaintext attribute
     */
    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            byte[] decoded = Base64.getDecoder().decode(dbData);
            ByteBuffer buffer = ByteBuffer.wrap(decoded);

            byte version = buffer.get();
            SecretKey key = keyStore.get(version);

            if (key == null) {
                throw new IllegalStateException("Unknown Key Version: " + version);
            }

            byte[] iv = new byte[IV_LENGTH_BYTE];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ENCRYPT_ALGO);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);

        } catch (IllegalStateException e) {
            // FIX: Re-throw logic errors (like Unknown Version) as-is, don't wrap them.
            throw e;
        } catch (javax.crypto.AEADBadTagException e) {
            // FIX: Specific handling for GCM Tag Mismatch (Tampering)
            log.error("SECURITY ALERT: Integrity Check Failed. Data was tampered with.");
            throw new IllegalStateException("Data Integrity Violation", e);
        } catch (Exception e) {
            // Catch-all for other crypto errors (Padding, Bad Key, etc.)
            log.error("Decryption Failed", e);
            throw new IllegalStateException("Decryption Error", e);
        }
    }

    // =========================================================================
    // BLIND INDEX
    // =========================================================================
    /**
     * Produces a deterministic, irreversible HMAC-SHA256 fingerprint of the supplied plaintext
     * for blind indexing—enabling equality searches without revealing sensitive data.
     * <p>
     * Commonly used to enforce PAN uniqueness without storing reversible values.
     *
     * @param plainText sensitive value to index
     * @return hexadecimal HMAC fingerprint
     */
    public static String blindIndex(String plainText) {
        if (INSTANCE == null) throw new IllegalStateException("Engine not ready");
        return INSTANCE.computeHmac(plainText);
    }

    /**
     * Computes the HMAC-SHA256 of the input using the configured hashing key.
     *
     * @param data plaintext to fingerprint
     * @return hexadecimal HMAC
     */
    private String computeHmac(String data) {
        // Guard against nulls to prevent NPEs in .getBytes()
        if (data == null) {
            throw new IllegalArgumentException("Cannot calculate Blind Index for NULL data.");
        }

        try {
            Mac mac = Mac.getInstance(HASH_ALGO);
            mac.init(hashingKey);

            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hmacBytes);

        } catch (java.security.NoSuchAlgorithmException e) {
            log.error("CRITICAL: HMAC Algorithm {} is missing from JVM.", HASH_ALGO);
            throw new IllegalStateException("HMAC Algorithm Not Found", e);
        } catch (java.security.InvalidKeyException e) {
            log.error("HMAC Key is Invalid. Check Key Generation logic.");
            throw new IllegalStateException("HMAC Key Invalid", e);
        } catch (Exception e) {
            log.error("Unexpected Error calculating Blind Index", e);
            throw new IllegalStateException("HMAC Calculation Failed", e);
        }
    }

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
    /**
     * Constant-time conversion of byte array to uppercase hexadecimal string.
     *
     * @param bytes raw binary data
     * @return hex representation without leading zeros
     */
    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }
}