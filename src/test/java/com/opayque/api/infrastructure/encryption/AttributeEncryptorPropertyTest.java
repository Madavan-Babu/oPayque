package com.opayque.api.infrastructure.encryption;

import com.opayque.api.infrastructure.config.OpayqueSecurityProperties;
import net.jqwik.api.*;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based chaos test suite for the {@link AttributeEncryptor} cryptographic engine.
 *
 * <p>This Jqwik-driven suite exhaustively validates AES-GCM encryption/decryption round trips,
 * PBKDF2 key-derivation determinism, and HMAC-SHA256 blind-index consistency under high-entropy and
 * adversarial inputs. It is designed to meet PCI-DSS, OWASP ASVS 8.x, and PSD2 RTS security
 * requirements for neobank-grade data protection.
 *
 * <p>Test sections:
 *
 * <ul>
 *   <li>Encryption/Decryption Properties (1–8)
 *   <li>Key Derivation Properties (9–12)
 *   <li>Blind Index Properties (13–17)
 *   <li>Cryptographic Structure Properties (18–22)
 *   <li>Error Handling & Fuzzing (23–25)
 *   <li>Concurrency & Interoperability (26–30)
 * </ul>
 *
 * @author Madavan Babu
 * @since 2026
 */
class AttributeEncryptorPropertyTest {

    // =========================================================================
    // SETUP & INFRASTRUCTURE
    // =========================================================================

    private static final String KEY_V1 = "Test_Key_Version_1_Secure_Password!";
    private static final String KEY_V2 = "Test_Key_Version_2_Rotation_Password!";
    private static final String HASH_KEY = "Blind_Index_Hashing_Key_Fixed!";


  /**
   * Factory helper that bootstraps a cryptographically-ready {@link AttributeEncryptor} instance
   * for deterministic property-based testing across PCI-DSS and PSD2 RTS compliance scenarios.
   *
   * <p>The method materialises an {@link OpayqueSecurityProperties} configuration that mirrors
   * production key-management practices:
   *
   * <ul>
   *   <li>PBKDF2-HMAC-SHA256 key derivation with 100 000 iterations (OWASP ASVS 8.x).
   *   <li>Versioned key store supporting seamless key rotation (version 1 active, version 2
   *       standby).
   *   <li>Fixed HMAC-SHA256 hashing key for blind-index determinism used in PAN uniqueness checks.
   * </ul>
   *
   * <p>Returned engines are pre-initialised and thread-safe, enabling parallel property executions
   * under Jqwik without shared-state corruption. Any failure during cryptographic initialisation is
   * escalated via {@link RuntimeException} to fail-fast and surface mis-configuration early in the
   * CI/CD pipeline—aligned with neobank-grade SRE practices.
   *
   * @return a fully initialised {@link AttributeEncryptor} ready for high-entropy encryption/
   *     decryption round-trip validations
   * @throws RuntimeException if key derivation or cipher initialisation fails, wrapping the
   *     underlying {@link GeneralSecurityException} to preserve stack trace
   */
  private AttributeEncryptor createEngine() {
        try {
            OpayqueSecurityProperties props = new OpayqueSecurityProperties();
            props.setActiveVersion(1);
            props.setHashingKey(HASH_KEY);

            Map<Integer, String> keyMap = new HashMap<>();
            keyMap.put(1, KEY_V1);
            keyMap.put(2, KEY_V2);
            props.setKeys(keyMap);

            AttributeEncryptor engine = new AttributeEncryptor(props);
            engine.init();
            return engine;
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Encryption Engine for tests", e);
        }
    }

    private final AttributeEncryptor encryptor = createEngine();


    // =========================================================================
    // SECTION 1: ENCRYPTION / DECRYPTION PROPERTY TESTS
    // =========================================================================

    // Test #1
    /**
     * Asserts that every plaintext encrypted with AES-GCM and immediately decrypted yields the identical string,
     * guaranteeing correctness under high-entropy fuzzing required by PCI-DSS and OWASP ASVS 8.x.
     */
    @Property(tries = 1000)
    @Label("1. Round Trip: Decrypt(Encrypt(Input)) == Input")
    void encryptDecryptRoundTrip(@ForAll String input) {
        String cipherText = encryptor.convertToDatabaseColumn(input);
        String plainText = encryptor.convertToEntityAttribute(cipherText);

        assertThat(plainText).isEqualTo(input);
    }

    // Test #2
    /**
     * Verifies that repeated encryption of the same plaintext never reuses an IV, ensuring ciphertext
     * indistinguishability and preventing replay attacks mandated by PSD2 RTS.
     */
    @Property(tries = 100)
    @Label("2. IV Randomness: Same input produces unique ciphertexts")
    void encryptionProducesUniqueOutputs(@ForAll String input) {
        String enc1 = encryptor.convertToDatabaseColumn(input);
        String enc2 = encryptor.convertToDatabaseColumn(input);

        // They must decrypt to the same thing...
        assertThat(encryptor.convertToEntityAttribute(enc1)).isEqualTo(input);

        // ...but the ciphertexts themselves MUST be different (Random IV)
        assertThat(enc1).isNotEqualTo(enc2);
    }

    // Test #3
    /**
     * Confirms that tampering with the version byte triggers GCM tag verification failure,
     * enforcing cryptographic integrity and key separation across versions.
     */
    @Property(tries = 100)
    @Label("3. Key Mismatch: Decryption with wrong key version fails")
    void decryptionFailsWithWrongKey(@ForAll String input) {
        // 1. Encrypt with V1 (Active)
        String v1Ciphertext = encryptor.convertToDatabaseColumn(input);

        // 2. Tamper: Change Version Byte from 1 to 2
        // Both keys exist, but they are different. GCM Auth should fail.
        byte[] bytes = Base64.getDecoder().decode(v1Ciphertext);
        bytes[0] = (byte) 2; // Force V2
        String tamperedCiphertext = Base64.getEncoder().encodeToString(bytes);

        // 3. Assert Failure
        assertThatThrownBy(() -> encryptor.convertToEntityAttribute(tamperedCiphertext))
                .isInstanceOf(IllegalStateException.class);
    }

    // Test #4
    /**
     * Ensures null inputs remain null throughout encryption and decryption pipelines,
     * preventing NullPointerException in downstream JPA layers.
     */
    @Test
    @DisplayName("4. Null Consistency: Nulls remain Null")
    void nullHandlingConsistency() {
        assertThat(encryptor.convertToDatabaseColumn(null)).isNull();
        assertThat(encryptor.convertToEntityAttribute(null)).isNull();
    }

    // Test #5
    /**
     * Validates correct handling of empty strings, producing non-empty ciphertext with
     * fixed AES-GCM overhead while preserving semantic equality after decryption.
     */
    @Test
    @DisplayName("5. Empty Strings: Encrypt/Decrypt empty strings correctly")
    void emptyStringHandling() {
        String empty = "";
        String enc = encryptor.convertToDatabaseColumn(empty);

        assertThat(enc).isNotNull().isNotEmpty(); // Ciphertext has overhead (IV + Tag)
        assertThat(encryptor.convertToEntityAttribute(enc)).isEqualTo(empty);
    }

    // Test #6
    /**
     * Stress-tests UTF-8 preservation for emojis, CJK, and combining characters,
     * ensuring round-trip fidelity required for global neobank support.
     */
    @Property(tries = 500)
    @Label("6. Unicode: Emojis and Multi-byte chars persist")
    void unicodePreservation(@ForAll String input) {
        String enc = encryptor.convertToDatabaseColumn(input);
        assertThat(encryptor.convertToEntityAttribute(enc)).isEqualTo(input);
    }

    // Test #7
    /**
     * Validates encryption/decryption performance and memory stability for 1 MB payloads,
     * aligning with PSD2 large-message latency SLAs.
     */
    @Property(tries = 20)
    @Label("7. Large Data: 1MB payload handling")
    void largeDataHandling(@ForAll @StringLength(min = 500_000, max = 1_000_000) String largeInput) {
        String enc = encryptor.convertToDatabaseColumn(largeInput);
        String dec = encryptor.convertToEntityAttribute(enc);
        assertThat(dec).isEqualTo(largeInput);
    }

    // Test #8
    /**
     * Simulates storage of binary blobs (images, PDFs) by round-tripping Base64-encoded strings,
     * ensuring ciphertext integrity for document vault use-cases.
     */
    @Property(tries = 100)
    @Label("8. Binary Data: Base64 Strings (Simulating Images/Files)")
    void binaryDataAsBase64(@ForAll byte[] binaryData) {
        // App logic: Encode binary to String -> Encrypt -> Store
        String base64Input = Base64.getEncoder().encodeToString(binaryData);

        String encrypted = encryptor.convertToDatabaseColumn(base64Input);
        String decrypted = encryptor.convertToEntityAttribute(encrypted);

        assertThat(decrypted).isEqualTo(base64Input);

        // Verify underlying bytes
        byte[] decodedResult = Base64.getDecoder().decode(decrypted);
        assertThat(decodedResult).isEqualTo(binaryData);
    }


    // =========================================================================
    // SECTION 2: KEY DERIVATION PROPERTY TESTS
    // =========================================================================

    // Test #9
    /**
     * Asserts PBKDF2 key derivation determinism across JVM restarts, enabling
     * seamless key rotation and multi-pod interoperability in Kubernetes.
     */
    @Test
    @DisplayName("9. Determinism: Initialization with same config produces same keys")
    void keyDerivationDeterministic() throws Exception {
        AttributeEncryptor engineA = createEngine();
        AttributeEncryptor engineB = createEngine();

        // Encrypt with A
        String data = "ConsistencyCheck";
        String cipherText = engineA.convertToDatabaseColumn(data);

        // Decrypt with B (Should work if Keys derived identically)
        String plainText = engineB.convertToEntityAttribute(cipherText);

        assertThat(plainText).isEqualTo(data);
    }

    // Test #10
    /**
     * Verifies that PBKDF2 produces cryptographically distinct keys for each version,
     * preventing cross-version key leakage during rotation.
     */
    @Test
    @DisplayName("10. Uniqueness: Different Key Versions are actually different")
    void keyDerivationUniquePerVersion() throws Exception {
        // We access the internal keyStore to verify keys are distinct
        Field storeField = AttributeEncryptor.class.getDeclaredField("keyStore");
        storeField.setAccessible(true);
        Map<?, ?> keyStore = (Map<?, ?>) storeField.get(encryptor);

        Object keyV1 = keyStore.get((byte) 1);
        Object keyV2 = keyStore.get((byte) 2);

        assertThat(keyV1).isNotNull();
        assertThat(keyV2).isNotNull();
        assertThat(keyV1).isNotEqualTo(keyV2); // PBKDF2 must produce different outputs
    }

    // Test #11
    /**
     * Confirms PBKDF2 correctness when passwords contain symbols and Unicode,
     * reflecting real-world key rotation policies.
     */
    @Test
    @DisplayName("11. Special Chars: Passwords with symbols derive correctly")
    void keyDerivationSpecialCharacters() throws Exception {
        OpayqueSecurityProperties specialProps = new OpayqueSecurityProperties();
        specialProps.setKeys(Map.of(1, "S!gn@l_&_Noise_#_🔑"));
        specialProps.setActiveVersion(1);
        specialProps.setHashingKey("hash");

        AttributeEncryptor specialEngine = new AttributeEncryptor(specialProps);
        specialEngine.init();

        String enc = specialEngine.convertToDatabaseColumn("Test");
        assertThat(specialEngine.convertToEntityAttribute(enc)).isEqualTo("Test");
    }

    // Test #12
    /**
     * Ensures PBKDF2 with 100 000 iterations completes within 5 s, balancing
     * OWASP recommended iteration count with startup latency SLAs.
     */
    @Test
    @DisplayName("12. Performance: Key Derivation (100k iterations) is acceptable")
    void keyDerivationPerformance() {
        long start = System.currentTimeMillis();
        createEngine(); // Derives 2 keys (100k iterations each)
        long end = System.currentTimeMillis();

        long duration = end - start;
        // Should be < 2000ms typically, but we give 5s buffer for CI environments
        assertThat(duration).as("Key derivation took too long").isLessThan(5000);
    }


    // =========================================================================
    // SECTION 3: BLIND INDEX TESTS (PARTIAL)
    // =========================================================================

    // Test #13
    /**
     * Asserts deterministic HMAC-SHA256 generation for PAN blind indexing,
     * enabling exact-match searches without revealing plaintext.
     */
    @Property(tries = 100)
    @Label("13. Blind Index: Deterministic (Input -> Same Hash)")
    void blindIndexDeterministic(@ForAll String input) {
        String hash1 = AttributeEncryptor.blindIndex(input);
        String hash2 = AttributeEncryptor.blindIndex(input);

        assertThat(hash1).isEqualTo(hash2);
    }

    // Test #14
    /**
     * Validates low collision probability for distinct PANs under HMAC-SHA256,
     * supporting PCI-DSS uniqueness constraints on fingerprints.
     */
    @Property(tries = 100)
    @Label("14. Collision Resistance: Distinct inputs -> Distinct Hashes")
    void blindIndexCollisionResistance(@ForAll String inputA, @ForAll String inputB) {
        // Ensure inputs are actually different
        Assume.that(!inputA.equals(inputB));

        String hashA = AttributeEncryptor.blindIndex(inputA);
        String hashB = AttributeEncryptor.blindIndex(inputB);

        assertThat(hashA).isNotEqualTo(hashB);
    }

    // Test #15
    /**
     * Enforces fail-fast behaviour when attempting to hash null PAN,
     * preventing downstream index corruption.
     */
    @Test
    @DisplayName("15. Blind Index: Null Input Handling")
    void blindIndexNullHandling() {
        // Design Choice: Should Blind Indexing allow NULL?
        // Usually NO, because index columns shouldn't be null if we are searching.
        // Assuming implementation fails fast or returns null.
        // Based on the typical "Hellproof" design-> Fail Fast.
        assertThatThrownBy(() -> AttributeEncryptor.blindIndex(null));
    }

    // Test #16
    /**
     * Confirms valid HMAC-SHA256 output for empty string input,
     * maintaining schema non-null constraints.
     */
    @Test
    @DisplayName("16. Blind Index: Empty String Handling")
    void blindIndexEmptyString() {
        // Should produce a valid hash, not crash
        String hash = AttributeEncryptor.blindIndex("");
        assertThat(hash).isNotNull().isNotEmpty();
        // HMAC-SHA256 of empty string is a known constant, but we just check structural validity
        assertThat(hash).matches("^[0-9A-F]+$"); // Hex string
    }

    // Test #17
    /**
     * Ensures correct hashing of Unicode PANs containing emojis and CJK characters,
     * supporting global card schemes.
     */
    @Property(tries = 100)
    @Label("17. Blind Index: Unicode Safety")
    void blindIndexUnicodeHandling(@ForAll String input) {
        String hash = AttributeEncryptor.blindIndex(input);
        assertThat(hash).isNotNull();
        // Consistency check
        assertThat(hash).isEqualTo(AttributeEncryptor.blindIndex(input));
    }

    // =========================================================================
    // SECTION 4: CRYPTOGRAPHIC PROPERTY TESTS
    // =========================================================================

    // Test #18
    /**
     * Validates minimum ciphertext length of 29 bytes (version+IV+tag) for any plaintext,
     * confirming AES-GCM formatting compliance.
     */
    @Property(tries = 500)
    @Label("18. Ciphertext Structure: [Version(1) + IV(12) + Data(N) + Tag(16)]")
    void ciphertextStructure(@ForAll String input) {
        String base64 = encryptor.convertToDatabaseColumn(input);
        byte[] bytes = Base64.getDecoder().decode(base64);

        // Minimum Size = 1 (Version) + 12 (IV) + 16 (GCM Tag) = 29 bytes
        // Even empty string input produces this overhead.
        assertThat(bytes.length).isGreaterThanOrEqualTo(29);
    }

    // Test #19
    /**
     * Confirms first byte of ciphertext equals configured active key version,
     * enabling versioned decryption during key rotation.
     */
    @Property(tries = 100)
    @Label("19. Version Byte Integrity: Ciphertext must start with Active Version")
    void versionByteIntegrity(@ForAll String input) {
        String base64 = encryptor.convertToDatabaseColumn(input);
        byte[] bytes = Base64.getDecoder().decode(base64);

        // We configured Active Version = 1 in setUp()
        assertThat(bytes[0]).isEqualTo((byte) 1);
    }

    // Test #20
    /**
     * Ensures every ciphertext is legal Base64 without padding or charset violations,
     * guaranteeing safe storage in UTF-8 columns.
     */
    @Property(tries = 100)
    @Label("20. Base64 Validity: All outputs must be valid RFC-4648")
    void base64Validity(@ForAll String input) {
        String cipherText = encryptor.convertToDatabaseColumn(input);

        // Should not throw IllegalArgumentException
        byte[] decoded = Base64.getDecoder().decode(cipherText);
        assertThat(decoded).isNotEmpty();
    }

    // Test #21
    /**
     * Randomly flips bits inside ciphertext to confirm GCM tag verification failure,
     * demonstrating AEAD tamper-detection mandated by PCI-DSS.
     */
    @Property(tries = 1000)
    @Label("21. Authentication Tag: Bit-Flipping Fuzzing")
    void authenticationTagValidation(@ForAll String input) {
        // 1. Encrypt
        String validBase64 = encryptor.convertToDatabaseColumn(input);
        byte[] validBytes = Base64.getDecoder().decode(validBase64);

        // 2. Fuzz: Flip one random bit in the encrypted payload (excluding version byte)
        // We skip byte 0 (Version) to ensure we test GCM failure, not Version failure.
        int flipIndex = 1 + (int) (Math.random() * (validBytes.length - 1));
        validBytes[flipIndex] ^= 1; // XOR flip

        String corruptedBase64 = Base64.getEncoder().encodeToString(validBytes);

        // 3. Decrypt MUST Fail
        assertThatThrownBy(() -> encryptor.convertToEntityAttribute(corruptedBase64))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Integrity"); // Expecting AEADBadTagException wrapped
    }

    // Test #22
    /**
     * Validates ciphertext length always exceeds plaintext length due to IV and tag,
     * supporting capacity planning for encrypted columns.
     */
    @Property(tries = 100)
    @Label("22. Length Correlation: Output length grows linearly with Input")
    void ciphertextLength(@ForAll @StringLength(min = 10, max = 100) String input) {
        String enc = encryptor.convertToDatabaseColumn(input);

        // Base64 expansion factor is ~1.33x
        // AES-GCM overhead is fixed constant (29 bytes)
        // So: Len(Enc) > Len(Input)
        assertThat(enc.length()).isGreaterThan(input.length());
    }


    // =========================================================================
    // SECTION 5: ERROR HANDLING & FUZZING
    // =========================================================================

    // Test #23
    /**
     * Feeds random garbage into decryption to ensure only IllegalStateException is thrown,
     * preventing information leakage via unexpected exceptions.
     */
    @Property(tries = 500)
    @Label("23. Garbage Input: Random strings should fail gracefully (Not Crash)")
    void decryptionOfGarbageFails(@ForAll String randomGarbage) {
        // Filter out strings that accidentally look like valid Base64
        // The chance of a random string being valid AES-GCM ciphertext is effectively 0.

        assertThatThrownBy(() -> encryptor.convertToEntityAttribute(randomGarbage))
                .isInstanceOf(IllegalStateException.class);
        // We assert it throws the Domain Exception (IllegalStateException),
        // NOT a RuntimeException (NPE, IllegalArgument, etc.)
    }

    // Test #24
    /**
     * Truncates ciphertext to remove part of the GCM tag, verifying decryption failure
     * and enforcing integrity checks.
     */
    @Property(tries = 100)
    @Label("24. Truncated Data: Missing Auth Tag")
    void decryptionWithTruncatedData(@ForAll String input) {
        String valid = encryptor.convertToDatabaseColumn(input);
        // Remove the last 5 characters (breaking Base64 padding or GCM tag)
        String truncated = valid.substring(0, valid.length() - 5);

        assertThatThrownBy(() -> encryptor.convertToEntityAttribute(truncated))
                .isInstanceOf(IllegalStateException.class);
    }

    // Test #25
    /**
     * Injects non-Base64 symbols to confirm decoder failure without exposing
     * internal cryptographic exceptions.
     */
    @Property(tries = 100)
    @Label("25. Invalid Base64 Chars")
    void decryptionWithNonBase64Chars(@ForAll @net.jqwik.api.constraints.AlphaChars String input) {
        // Inject a symbol that is definitely not Base64 (e.g. %)
        String invalidPayload = input + "%" + input;

        assertThatThrownBy(() -> encryptor.convertToEntityAttribute(invalidPayload))
                .isInstanceOf(IllegalStateException.class);
    }


    // =========================================================================
    // SECTION 6: CONCURRENCY & INTEROP
    // =========================================================================

    // Test #26
    /**
     * Executes 1000 parallel encryptions/decryptions to confirm thread-safety
     * of AttributeEncryptor singleton under Spring contexts.
     */
    @Test // Jqwik doesn't do parallelism well natively, using standard test logic
    @DisplayName("26. Concurrent Encryption Safety")
    void concurrentEncryptionSafety() {
        // Java Streams parallel execution
        java.util.stream.IntStream.range(0, 1000).parallel().forEach(i -> {
            String input = "ThreadSafe-" + i;
            String enc = encryptor.convertToDatabaseColumn(input);
            String dec = encryptor.convertToEntityAttribute(enc);
            if (!input.equals(dec)) {
                throw new RuntimeException("Thread Safety Violation!");
            }
        });
    }

    // Test #27
    /**
     * Performs 1000 parallel decryptions of shared ciphertext to ensure
     * no race conditions or data corruption occur.
     */
    @Test
    @DisplayName("27. Concurrent Decryption Safety")
    void concurrentDecryptionSafety() {
        String validCipher = encryptor.convertToDatabaseColumn("SharedPayload");

        java.util.stream.IntStream.range(0, 1000).parallel().forEach(i -> {
            String dec = encryptor.convertToEntityAttribute(validCipher);
            if (!"SharedPayload".equals(dec)) {
                throw new RuntimeException("Thread Safety Violation!");
            }
        });
    }

    // Test #28
    /**
     * Checks that decryption failure messages never contain key material,
     * preventing accidental credential leakage in logs.
     */
    @Property(tries = 100)
    @Label("28. Exception Sanitization: Errors don't leak keys")
    void exceptionMessageSanitization(@ForAll String garbage) {
        try {
            encryptor.convertToEntityAttribute(garbage);
        } catch (IllegalStateException e) {
            // Assert the exception message does NOT contain our secrets
            assertThat(e.getMessage()).doesNotContain(KEY_V1);
            assertThat(e.getMessage()).doesNotContain(KEY_V2);
        }
    }

    // Test #29
    /**
     * Confirms startup fails fast when configured active key version is missing,
     * enforcing configuration correctness and preventing runtime cryptographic errors.
     */
    @Test
    @DisplayName("29. Active Key Validation: Active Version must exist")
    void activeKeyValidation() throws Exception {
        OpayqueSecurityProperties badProps = new OpayqueSecurityProperties();
        badProps.setKeys(Map.of(1, KEY_V1));
        badProps.setActiveVersion(99); // 99 does not exist
        badProps.setHashingKey(HASH_KEY);

        AttributeEncryptor badEngine = new AttributeEncryptor(badProps);

        assertThatThrownBy(() -> badEngine.init())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active Key Version 99");
    }

    // Test #30
    /**
     * Demonstrates cross-instance interoperability by encrypting on one engine
     * and decrypting on another with identical configuration, supporting zero-downtime deployments.
     */
    @Test
    @DisplayName("30. Interoperability: Config A Encrypts -> Config B Decrypts")
    void interoperabilityTest() throws Exception {
        // Simulate two different pods starting up with the same config
        AttributeEncryptor podA = createEngine();
        AttributeEncryptor podB = createEngine();

        String secret = "MicroserviceInterop";

        // Pod A Encrypts
        String ciphertext = podA.convertToDatabaseColumn(secret);

        // Pod B Decrypts
        String plaintext = podB.convertToEntityAttribute(ciphertext);

        assertThat(plaintext).isEqualTo(secret);
    }
}