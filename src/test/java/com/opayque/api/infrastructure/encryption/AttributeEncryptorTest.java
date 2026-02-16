package com.opayque.api.infrastructure.encryption;

import com.opayque.api.infrastructure.config.OpayqueSecurityProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;


/**
 * Epic 4: Cryptographic Assurance for PCI-DSS Compliance (V2 - Fortress Edition).
 * <p>
 * Comprehensive unit-test suite for the refactored {@link AttributeEncryptor}.
 * Validates AES-256-GCM, PBKDF2 key-derivation, blind indexing, and key-rotation logic.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 */
class AttributeEncryptorTest {

    private AttributeEncryptor encryptor;
    private static final String KEY_V1 = "Passphrase_Number_One_For_Testing!";
    private static final String KEY_V2 = "Passphrase_Number_Two_For_Rotation!";
    private static final String HASH_KEY = "Blind_Index_Hashing_Key_For_Tests!";


    private OpayqueSecurityProperties props; // Store this to modify it in tests

    /**
     * Instantiates the encryptor with two key versions and a hashing key for blind indexing.
     * Mimics a PCI-DSS compliant key-vault rotation scenario.
     */
    @BeforeEach
    void setUp() throws Exception {
        // 1. Create Config POJO
        props = new OpayqueSecurityProperties();
        props.setActiveVersion(1);
        props.setHashingKey(HASH_KEY);

        Map<Integer, String> keyMap = new HashMap<>();
        keyMap.put(1, KEY_V1);
        keyMap.put(2, KEY_V2);
        props.setKeys(keyMap);

        // 2. Inject via Constructor (No Reflection needed!)
        encryptor = new AttributeEncryptor(props);
        encryptor.init();
    }


    // =========================================================================
    // 1. CORE ENCRYPTION / DECRYPTION
    // =========================================================================
    /**
     * Validates core cryptographic correctness: round-trip encryption, null safety,
     * deterministic randomness via unique IVs, and empty-string handling.
     */
    @Nested
    @DisplayName("Core Crypto Operations")
    class CoreOperations {

        /**
         * Ensures a 16-digit PAN is encrypted to a non-equal Base64 string and
         * successfully decrypted back to the original value.
         */
        @Test
        @DisplayName("Happy Path: Should encrypt and decrypt valid data")
        void testEncryptionWithValidData() {
            String original = "4000123456789010";
            String encrypted = encryptor.convertToDatabaseColumn(original);

            assertThat(encrypted).isNotNull().isNotEqualTo(original);

            // Decrypt
            String decrypted = encryptor.convertToEntityAttribute(encrypted);
            assertThat(decrypted).isEqualTo(original);
        }

        /**
         * Confirms that null plaintext and null ciphertext are handled without NPE,
         * satisfying OWASP ASVS 8.3 null-safety requirements.
         */
        @Test
        @DisplayName("Null Safety: Should return null for null inputs")
        void testEncryptionWithNullInput() {
            assertThat(encryptor.convertToDatabaseColumn(null)).isNull();
            assertThat(encryptor.convertToEntityAttribute(null)).isNull();
        }

        /**
         * Verifies that empty strings are encrypted and decrypted deterministically,
         * ensuring no data loss or trimming side effects.
         */
        @Test
        @DisplayName("Empty Strings: Should handle empty strings correctly")
        void testEncryptionWithEmptyString() {
            String empty = "";
            String encrypted = encryptor.convertToDatabaseColumn(empty);
            assertThat(encryptor.convertToEntityAttribute(encrypted)).isEqualTo(empty);
        }

        /**
         * Confirms that identical plaintext produces distinct ciphertexts due to
         * random 96-bit IVs, a mandatory property for GCM semantic security.
         */
        @Test
        @DisplayName("Randomness: Same plaintext must produce DIFFERENT ciphertext (IV Uniqueness)")
        void testEncryptionGeneratesUniqueOutput() {
            String data = "SensitiveData";
            String enc1 = encryptor.convertToDatabaseColumn(data);
            String enc2 = encryptor.convertToDatabaseColumn(data);

            assertThat(enc1).isNotEqualTo(enc2);

            // Both should decrypt to the same value
            assertThat(encryptor.convertToEntityAttribute(enc1)).isEqualTo(data);
            assertThat(encryptor.convertToEntityAttribute(enc2)).isEqualTo(data);
        }
    }

    // =========================================================================
    // 2. INTEGRITY & TAMPERING (AES-GCM)
    // =========================================================================
    /**
     * Ensures AES-GCM authentication tags reject tampered or truncated ciphertext,
     * fulfilling PCI-DSS requirement 4.1 for data integrity.
     */
    @Nested
    @DisplayName("Integrity & Security")
    class IntegrityTests {

        /**
         * Flips one bit in the authentication tag and asserts that decryption
         * throws an integrity violation, proving GCM tag verification.
         */
        @Test
        @DisplayName("Tampering: Should fail if ciphertext is modified (GCM Auth Tag Check)")
        void testDecryptionWithModifiedCiphertext() {
            String original = "Don'tTouchMe";
            String encryptedBase64 = encryptor.convertToDatabaseColumn(original);

            // Decode, flip a bit, Re-encode
            byte[] bytes = Base64.getDecoder().decode(encryptedBase64);
            bytes[bytes.length - 1] ^= 1; // Flip the last bit of Auth Tag
            String corruptedBase64 = Base64.getEncoder().encodeToString(bytes);

            assertThatThrownBy(() -> encryptor.convertToEntityAttribute(corruptedBase64))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Data Integrity Violation");
            // Matches the "AEADBadTagException" catch block
        }

        /**
         * Truncates the ciphertext and expects an IllegalStateException,
         * validating that short payloads are rejected before tag verification.
         */
        @Test
        @DisplayName("Truncation: Should fail if data is incomplete")
        void testDecryptionWithTruncatedData() {
            String encrypted = encryptor.convertToDatabaseColumn("ShortData");
            String truncated = encrypted.substring(0, encrypted.length() - 5);

            assertThatThrownBy(() -> encryptor.convertToEntityAttribute(truncated))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // =========================================================================
    // 3. KEY ROTATION & VERSIONING
    // =========================================================================
    /**
     * Simulates cryptographic key rotation mandated by PCI-DSS 3.5.3,
     * ensuring decryptability of data encrypted under retired keys.
     */
    @Nested
    @DisplayName("Key Rotation & Versioning")
    class RotationTests {


        /**
         * Encrypts with V1, rotates to V2, and verifies that both old and new
         * ciphertexts remain decryptable, demonstrating zero-downtime rotation.
         */
        @Test
        @DisplayName("Rotation: Should decrypt data encrypted with Old Key (V1) while V2 is active")
        void testDecryptionAcrossKeyVersions() throws Exception {
            // 1. V1 is active (from setUp)
            String v1Ciphertext = encryptor.convertToDatabaseColumn("OldSecret");

            // 2. Rotate Keys: Update POJO and Re-init
            props.setActiveVersion(2);
            encryptor.init();

            // 3. Assert New Writes use V2
            String newCiphertext = encryptor.convertToDatabaseColumn("NewSecret");
            byte[] newBytes = Base64.getDecoder().decode(newCiphertext);
            assertThat(newBytes[0]).isEqualTo((byte) 2);

            // 4. Assert Old Reads still work (V1)
            assertThat(encryptor.convertToEntityAttribute(v1Ciphertext)).isEqualTo("OldSecret");
        }

        /**
         * Crafts a payload with an unmapped version byte (99) and asserts
         * an informative error, preventing downgrade attacks.
         */
        @Test
        @DisplayName("Unknown Version: Should fail if version byte is not in KeyStore")
        void testDecryptionWithUnknownKeyVersion() {
            // Manually construct a payload with Version 99
            byte[] maliciousPayload = new byte[20];
            maliciousPayload[0] = (byte) 99; // Version 99
            String badData = Base64.getEncoder().encodeToString(maliciousPayload);

            assertThatThrownBy(() -> encryptor.convertToEntityAttribute(badData))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unknown Key Version: 99");
        }

        /**
         * Attempts to initialize with an active version whose key is absent,
         * expecting a clear exception to avoid silent failures.
         */
        @Test
        void testEncryptionWithMissingActiveKey() throws Exception {
            // Set invalid version
            props.setActiveVersion(5);

            assertThatThrownBy(() -> encryptor.init())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Active Key Version 5 not found");
        }
    }

    // =========================================================================
    // 4. BLIND INDEXING
    // =========================================================================
    /**
     * Validates the HMAC-SHA256 blind index used for PAN existence checks
     * without revealing plaintext, satisfying PCI-DSS 3.4.1 one-way hashing.
     */
    @Nested
    @DisplayName("Blind Indexing")
    class BlindIndexTests {

        /**
         * Asserts that identical PANs always yield identical fingerprints,
         * enabling idempotent duplicate checks.
         */
        @Test
        @DisplayName("Consistency: Same Input -> Same Hash (Deterministic)")
        void testBlindIndexConsistency() {
            String pan = "4111222233334444";
            String hash1 = AttributeEncryptor.blindIndex(pan);
            String hash2 = AttributeEncryptor.blindIndex(pan);

            assertThat(hash1).isEqualTo(hash2);
        }

        /**
         * Ensures that differing inputs produce differing hashes,
         * minimizing collision probability under the birthday bound.
         */
        @Test
        @DisplayName("Uniqueness: Different Input -> Different Hash")
        void testBlindIndexWithDifferentInputs() {
            String hash1 = AttributeEncryptor.blindIndex("A");
            String hash2 = AttributeEncryptor.blindIndex("B");

            assertThat(hash1).isNotEqualTo(hash2);
        }

        /**
         * Confirms that null input triggers an exception, enforcing
         * fail-fast semantics for indexing misuse.
         */
        @Test
        @DisplayName("Null Safety: Should throw or handle nulls (Design choice: Fail fast or return null)")
        void testBlindIndexWithNullInput() {
            // Based on your implementation, computeHmac(null) would throw NPE or similar
            // Let's assume we want to fail fast if someone tries to index a NULL pan
            assertThatThrownBy(() -> AttributeEncryptor.blindIndex(null))
                    .isInstanceOf(Exception.class);
        }
    }

    // =========================================================================
    // 5. EDGE CASES & CONCURRENCY
    // =========================================================================
    /**
     * Covers Unicode payloads, 4-KB large data, and concurrent load to ensure
     * thread-safety and robustness under FinTech traffic spikes.
     */
    @Nested
    @DisplayName("Edge Cases & Load")
    class EdgeCases {

        /**
         * Encrypts and decrypts multilingual and emoji payloads to confirm
         * UTF-8 byte preservation across the AES-GCM cipher.
         */
        @ParameterizedTest
        @DisplayName("Unicode: Should handle Emoji and International characters")
        @ValueSource(strings = {"Hello World", "你好", "🔒💳💰", "مرحبا"})
        void testUnicodeDataEncryption(String input) {
            String enc = encryptor.convertToDatabaseColumn(input);
            String dec = encryptor.convertToEntityAttribute(enc);
            assertThat(dec).isEqualTo(input);
        }

        /**
         * Encrypts a 4-KB string to verify that the implementation handles
         * large blobs without buffer overflows or performance degradation.
         */
        @Test
        @DisplayName("Large Data: Should handle larger payloads (e.g. 4KB)")
        void testLargeDataEncryption() {
            String largeString = "A".repeat(4096); // 4KB
            String enc = encryptor.convertToDatabaseColumn(largeString);
            String dec = encryptor.convertToEntityAttribute(enc);
            assertThat(dec).isEqualTo(largeString);
        }

        /**
         * Spins 20 threads × 100 iterations to assert zero decryption mismatches,
         * proving thread-safety of the singleton encryptor.
         */
        @Test
        @DisplayName("Concurrency: Should be thread-safe under load")
        void testConcurrentEncryption() throws InterruptedException {
            int threads = 20;
            int iterations = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threads);
            CountDownLatch latch = new CountDownLatch(threads);
            AtomicInteger failures = new AtomicInteger(0);

            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < iterations; j++) {
                            String data = "ThreadData-" + Thread.currentThread().getId() + "-" + j;
                            String enc = encryptor.convertToDatabaseColumn(data);
                            String dec = encryptor.convertToEntityAttribute(enc);
                            if (!data.equals(dec)) {
                                failures.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        failures.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(failures.get()).isEqualTo(0);
        }
    }

    // =========================================================================
    // 6. CODE COVERAGE: ERROR HANDLING & CONFIG VALIDATION
    // =========================================================================
    /**
     * Exercises failure paths: missing config, KDF/Cipher/Mac exceptions,
     * and uninitialized state to guarantee robust error messages.
     */
    @Nested
    @DisplayName("Corner Cases & Error Handling")
    class ErrorHandlingTests {

        // --- Lines 1-3: Configuration Validation ---

        /**
         * Initializes with a null active-version and expects a clear message
         * to avoid silent misconfigurations in production vaults.
         */
        @Test
        @DisplayName("Init: Should fail if Active Version is NULL")
        void init_MissingActiveVersion() {
            OpayqueSecurityProperties badProps = new OpayqueSecurityProperties();
            badProps.setKeys(Map.of(1, KEY_V1));
            badProps.setHashingKey(HASH_KEY);
            // activeVersion is null by default

            AttributeEncryptor badEngine = new AttributeEncryptor(badProps);

            assertThatThrownBy(badEngine::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("active-version is missing");
        }

        /**
         * Mocks Mac.doFinal to throw RuntimeException and verifies that the
         * generic catch block wraps it as IllegalStateException.
         */
        @Test
        @DisplayName("Blind Index: Should handle Unexpected Errors (Generic Catch)")
        void blindIndex_UnexpectedError() {
            encryptor.init(); // Ensure INSTANCE is ready

            // 1. Create a Mock Mac that throws RuntimeException on execution
            // (This triggers the generic 'catch (Exception e)' block)
            Mac macMock = Mockito.mock(Mac.class);
            Mockito.when(macMock.doFinal(any())).thenThrow(new RuntimeException("Unexpected Crash"));

            // 2. Mock the Static Factory to return our ticking time-bomb
            try (MockedStatic<Mac> mockStaticMac = Mockito.mockStatic(Mac.class)) {
                mockStaticMac.when(() -> Mac.getInstance(anyString())).thenReturn(macMock);

                // 3. Execute and Assert
                assertThatThrownBy(() -> AttributeEncryptor.blindIndex("Data"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("HMAC Calculation Failed") // Matches your throw
                        .hasRootCauseMessage("Unexpected Crash");
            }
        }

        /**
         * Tests both null and empty key maps to ensure early failure with
         * descriptive messages for DevSecOps diagnostics.
         */
        @Test
        @DisplayName("Init: Should fail if Keys Map is NULL or Empty")
        void init_MissingKeys() {
            // Case 1: Null Keys
            OpayqueSecurityProperties nullKeys = new OpayqueSecurityProperties();
            nullKeys.setActiveVersion(1);
            nullKeys.setHashingKey(HASH_KEY);
            nullKeys.setKeys(null);

            assertThatThrownBy(() -> new AttributeEncryptor(nullKeys).init())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("keys is empty or missing");

            // Case 2: Empty Keys
            OpayqueSecurityProperties emptyKeys = new OpayqueSecurityProperties();
            emptyKeys.setActiveVersion(1);
            emptyKeys.setHashingKey(HASH_KEY);
            emptyKeys.setKeys(Collections.emptyMap());

            assertThatThrownBy(() -> new AttributeEncryptor(emptyKeys).init())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("keys is empty or missing");
        }

        /**
         * Omits the hashing key and asserts that blind-index initialization
         * fails fast, preventing silent PAN leakage.
         */
        @Test
        @DisplayName("Init: Should fail if Hashing Key is NULL")
        void init_MissingHashingKey() {
            OpayqueSecurityProperties noHash = new OpayqueSecurityProperties();
            noHash.setActiveVersion(1);
            noHash.setKeys(Map.of(1, KEY_V1));
            // hashingKey is null

            assertThatThrownBy(() -> new AttributeEncryptor(noHash).init())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("hashing-key is missing");
        }

        // --- Line 4: KDF Failure (SecretKeyFactory) ---

        /**
         * Mocks SecretKeyFactory to throw NoSuchAlgorithmException and ensures
         * the KDF failure is wrapped with a clear message.
         */
        @Test
        @DisplayName("KDF: Should wrap NoSuchAlgorithmException")
        void deriveKey_KdfFailure() {
            // We mock the static SecretKeyFactory to throw an exception even with valid inputs
            try (MockedStatic<SecretKeyFactory> mockFactory = Mockito.mockStatic(SecretKeyFactory.class)) {
                mockFactory.when(() -> SecretKeyFactory.getInstance(anyString()))
                        .thenThrow(new NoSuchAlgorithmException("Forced KDF Error"));

                // Attempting to init will trigger deriveKey()
                OpayqueSecurityProperties props = new OpayqueSecurityProperties();
                props.setActiveVersion(1);
                props.setKeys(Map.of(1, KEY_V1));
                props.setHashingKey(HASH_KEY);

                AttributeEncryptor engine = new AttributeEncryptor(props);

                assertThatThrownBy(engine::init)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("KDF Failure")
                        .hasRootCauseInstanceOf(NoSuchAlgorithmException.class);
            }
        }

        // --- Line 5: Encryption Failures (Cipher) ---

        /**
         * Injects an incompatible DES key into the key store to trigger
         * InvalidKeyException during AES cipher init.
         */
        @Test
        @DisplayName("Encryption: Should handle InvalidKeyException")
        void encrypt_InvalidKeyException() {
            // 1. Initialize normally
            encryptor.init();

            // 2. Corrupt the key store via reflection to force InvalidKeyException during Cipher.init
            // We insert a key that is valid Java object but has an invalid algorithm for AES/GCM
            try {
                Field keyStoreField = AttributeEncryptor.class.getDeclaredField("keyStore");
                keyStoreField.setAccessible(true);
                Map<Byte, SecretKey> keyStore = (Map<Byte, SecretKey>) keyStoreField.get(encryptor);

                // "DES" key cannot be used with "AES" Cipher -> throws InvalidKeyException
                SecretKey badKey = new SecretKeySpec(new byte[8], "DES");
                keyStore.put((byte) 1, badKey);

                assertThatThrownBy(() -> encryptor.convertToDatabaseColumn("Data"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Encryption Key Error")
                        .hasRootCauseInstanceOf(InvalidKeyException.class);

            } catch (Exception e) {
                throw new RuntimeException("Reflection failed", e);
            }
        }

        /**
         * Mocks Cipher.getInstance to throw NoSuchPaddingException and
         * verifies that encryption system failures are wrapped appropriately.
         */
        @Test
        @DisplayName("Encryption: Should handle GeneralSecurityException")
        void encrypt_SystemFailure() {
            // Force Cipher.getInstance() to throw
            try (MockedStatic<Cipher> mockCipher = Mockito.mockStatic(Cipher.class)) {
                mockCipher.when(() -> Cipher.getInstance(anyString()))
                        .thenThrow(new javax.crypto.NoSuchPaddingException("System integrity lost"));

                assertThatThrownBy(() -> encryptor.convertToDatabaseColumn("Data"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Encryption System Failure");
            }
        }

        /**
         * Forces a RuntimeException during cipher instantiation to ensure
         * all unforeseen encryption errors are captured.
         */
        @Test
        @DisplayName("Encryption: Should handle Unexpected Runtime Exceptions")
        void encrypt_UnexpectedFailure() {
            try (MockedStatic<Cipher> mockCipher = Mockito.mockStatic(Cipher.class)) {
                mockCipher.when(() -> Cipher.getInstance(anyString()))
                        .thenThrow(new RuntimeException("Total Collapse"));

                assertThatThrownBy(() -> encryptor.convertToDatabaseColumn("Data"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Encryption Failed");
            }
        }

        // --- Line 6: Engine Not Ready ---

        /**
         * Nullifies the static INSTANCE field and asserts that blind-index
         * calls fail with an informative message when the engine is uninitialized.
         */
        @Test
        @DisplayName("Blind Index: Should fail if Engine not initialized (Static State)")
        void blindIndex_NotReady() throws Exception {
            // 1. Force INSTANCE to null (simulate Pre-Init state)
            Field instanceField = AttributeEncryptor.class.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            instanceField.set(null, null);

            assertThatThrownBy(() -> AttributeEncryptor.blindIndex("Data"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Engine not ready");
        }

        // --- Line 7: HMAC Failures (Mac) ---

        /**
         * Mocks Mac.getInstance to throw NoSuchAlgorithmException and
         * ensures the blind-index reports “HMAC Algorithm Not Found”.
         */
        @Test
        @DisplayName("Blind Index: Should handle Missing Algorithm")
        void blindIndex_NoSuchAlgorithm() {
            encryptor.init(); // Ensure INSTANCE is set

            try (MockedStatic<Mac> mockMac = Mockito.mockStatic(Mac.class)) {
                mockMac.when(() -> Mac.getInstance(anyString()))
                        .thenThrow(new NoSuchAlgorithmException("HMAC Missing"));

                assertThatThrownBy(() -> AttributeEncryptor.blindIndex("Data"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("HMAC Algorithm Not Found");
            }
        }

        /**
         * Mocks Mac.init to throw InvalidKeyException and verifies that
         * the blind-index reports “HMAC Key Invalid” for diagnostic clarity.
         */
        @Test
        @DisplayName("Blind Index: Should handle Invalid Key")
        void blindIndex_InvalidKey() throws Exception{
            encryptor.init();

            // Mock Mac to throw InvalidKeyException on init()
            Mac macMock = Mockito.mock(Mac.class);
            Mockito.doThrow(new InvalidKeyException("Bad Key")).when(macMock).init(any());

            try (MockedStatic<Mac> mockMac = Mockito.mockStatic(Mac.class)) {
                mockMac.when(() -> Mac.getInstance(anyString())).thenReturn(macMock);

                assertThatThrownBy(() -> AttributeEncryptor.blindIndex("Data"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("HMAC Key Invalid");
            } catch (Exception e) {
                // Should not happen, just for checked exception signature
            }
        }
    }
}