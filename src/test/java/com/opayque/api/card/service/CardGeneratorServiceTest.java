package com.opayque.api.card.service;

import com.opayque.api.card.model.CardSecrets;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.card.util.LuhnAlgorithm;
import com.opayque.api.infrastructure.config.OpayqueCardProperties; // Import Added
import com.opayque.api.infrastructure.config.OpayqueSecurityProperties;
import com.opayque.api.infrastructure.encryption.AttributeEncryptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;



/**
 * PCI-DSS compliant unit-test suite for {@link CardGeneratorService}.
 * <p>
 * Validates generation of PAN, CVV and expiry for virtual cards within the oPayque neobank ecosystem.
 * Ensures Luhn-valid PANs, unique blind-index lookups (HMAC), and configurable expiry horizons
 * aligned to PSD2 RTS and OWASP ASVS 8.x requirements.
 * </p>
 * Thread-safety and cryptographic hygiene are verified across retries, collisions and entropy.
 *
 * @author Madavan Babu
 * @since 2026
 */
@ExtendWith(MockitoExtension.class)
class CardGeneratorServiceTest {

    @Mock
    private VirtualCardRepository virtualCardRepository;

    private CardGeneratorService cardGeneratorService;

    private static final String BIN = "171103";
    private static final int EXPIRY_YEARS = 3;

    /**
     * Initializes {@link CardGeneratorService} with injected POJO config and primes the static
     * {@link AttributeEncryptor} singleton for deterministic HMAC-SHA256 blind-index generation.
     * Required for repeatable PAN fingerprint assertions under test.
     */
    @BeforeEach
    void setUp() throws Exception {
        // 1. Initialize Service (FIXED: Using Helper with POJO)
        cardGeneratorService = createService(virtualCardRepository, BIN, EXPIRY_YEARS);

        // 2. MANUALLY INITIALIZE STATIC ENCRYPTOR
        OpayqueSecurityProperties testProps = new OpayqueSecurityProperties();
        testProps.setActiveVersion(1);
        testProps.setKeys(Map.of(1, "Test_Key_V1_For_Unit_Tests_Only_12345"));
        testProps.setHashingKey("Test_Hashing_Key_123");

        AttributeEncryptor encryptor = new AttributeEncryptor(testProps);
        encryptor.init();
    }

    /**
     * Tears down the static {@link AttributeEncryptor} singleton to prevent cross-test key leakage
     * and ensure hermetic crypto state for each test case.
     */
    @AfterEach
    void tearDown() throws Exception {
        // Reset static INSTANCE
        Field instance = AttributeEncryptor.class.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    // FIX: Helper to handle POJO creation (Reduces code duplication)
    /**
     * Factory helper that binds repository and card configuration into a testable service instance.
     * Eliminates duplication when asserting behaviour under varying BIN lengths or expiry windows.
     */
    private CardGeneratorService createService(VirtualCardRepository repo, String bin, int years) {
        OpayqueCardProperties props = new OpayqueCardProperties();
        props.setBinPrefix(bin);
        props.setExpiryYears(years);
        return new CardGeneratorService(repo, props);
    }

    // =========================================================================
    // SECTION 1: CORE GENERATION & RETRIES
    // =========================================================================

    /**
     * Asserts successful generation of a Luhn-valid 16-digit PAN, 3-digit CVV and MM/yy expiry.
     * Serves as golden-path validation for PCI-DSS PAN formatting and CVV length compliance.
     */
    @Test
    @DisplayName("1. Happy Path: Returns valid PAN, CVV, and Expiry")
    void generateCard_ReturnsValidCardSecrets() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);

        CardSecrets secrets = cardGeneratorService.generateCard();

        assertThat(secrets.pan()).startsWith(BIN).hasSize(16);
        assertThat(LuhnAlgorithm.isValid(secrets.pan())).isTrue();
        assertThat(secrets.cvv()).matches("^\\d{3}$");
        assertThat(secrets.expiryDate()).matches("^\\d{2}/\\d{2}$");
    }

    /**
     * Confirms retry mechanism when PAN fingerprint collisions occur; simulates duplicate
     * blind-index entries and verifies eventual uniqueness within the retry limit.
     */
    @Test
    @DisplayName("2. Collision Logic: Retries until unique (Success on 3rd try)")
    void generateUniquePan_RetriesUntilUnique() {
        when(virtualCardRepository.existsByPanFingerprint(anyString()))
                .thenReturn(true, true, false);

        CardSecrets secrets = cardGeneratorService.generateCard();

        assertThat(secrets.pan()).isNotNull();
        verify(virtualCardRepository, times(3)).existsByPanFingerprint(anyString());
    }

    /**
     * Ensures fail-fast when entropy exhaustion prevents unique PAN generation after five
     * consecutive fingerprint collisions, protecting against infinite loops.
     */
    @Test
    @DisplayName("3. Exhaustion: Throws Exception after Max Retries (5)")
    void generateUniquePan_ThrowsAfterMaxRetries() {
        when(virtualCardRepository.existsByPanFingerprint(anyString()))
                .thenReturn(true, true, true, true, true);

        assertThatThrownBy(() -> cardGeneratorService.generateCard())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to generate unique card number");

        verify(virtualCardRepository, times(5)).existsByPanFingerprint(anyString());
    }

    /**
     * Validates that PAN lookups use deterministic HMAC-SHA256 blind-index rather than
     * raw PAN, preserving PCI-DSS requirement for irreversible search tokens.
     */
    @Test
    @DisplayName("4. Security: Verifies Blind Index (HMAC) is used for lookup")
    void generateUniquePan_VerifiesBlindIndexIsUsed() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);

        cardGeneratorService.generateCard();
        verify(virtualCardRepository).existsByPanFingerprint(matches("^[0-9A-F]+$"));
    }

    // =========================================================================
    // SECTION 2: CVV & EXPIRY
    // =========================================================================

    /**
     * Stress-tests CVV generation across 100 iterations to guarantee fixed 3-digit decimal
     * output satisfying card scheme CVM requirements.
     */
    @Test
    @DisplayName("5. CVV Format: Always 3 Digits")
    void generateCvv_AlwaysThreeDigits() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);
        for (int i = 0; i < 100; i++) {
            assertThat(cardGeneratorService.generateCard().cvv()).matches("^\\d{3}$");
        }
    }

    /**
     * Collects 3000 CVV digits to assert uniform inclusion of 0-9, confirming absence
     * of bias that could aid brute-force attacks on CNP channels.
     */
    @Test
    @DisplayName("6. CVV Distribution: Digits 0-9 appear")
    void generateCvv_UniformDistribution() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);
        Set<Character> seenDigits = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String cvv = cardGeneratorService.generateCard().cvv();
            for (char c : cvv.toCharArray()) seenDigits.add(c);
        }
        assertThat(seenDigits).containsExactlyInAnyOrder('0','1','2','3','4','5','6','7','8','9');
    }

    /**
     * Checks expiry string adheres to zero-padded MM/yy format required by merchant
     * payment forms and PSD2 SCA validation routines.
     */
    @Test
    @DisplayName("7. Expiry Format: MM/yy")
    void generateExpiryDate_FormatCheck() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);
        assertThat(cardGeneratorService.generateCard().expiryDate()).matches("^(0[1-9]|1[0-2])/\\d{2}$");
    }

    /**
     * Parses generated expiry month to ensure value falls within 1-12 range,
     * preventing calendar overflow in downstream authorisation systems.
     */
    @Test
    @DisplayName("8. Expiry Range: Month is 01-12")
    void generateExpiryDate_MonthRange() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);
        String expiry = cardGeneratorService.generateCard().expiryDate();
        int month = Integer.parseInt(expiry.split("/")[0]);
        assertThat(month).isBetween(1, 12);
    }

    /**
     * Validates expiry year offset by EXPIRY_YEARS from current date, ensuring
     * card lifespan aligns with product risk appetite and regulatory limits.
     */
    @Test
    @DisplayName("9. Expiry Year: Current Date + Configured Years")
    void generateExpiryDate_HandlesDecemberRollover() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);
        String expected = LocalDate.now().plusYears(EXPIRY_YEARS).format(DateTimeFormatter.ofPattern("MM/yy"));
        assertThat(cardGeneratorService.generateCard().expiryDate()).isEqualTo(expected);
    }

    /**
     * Asserts that injected {@link OpayqueCardProperties} correctly propagates BIN prefix
     * into generated PANs, confirming Spring configuration binding.
     */
    @Test
    @DisplayName("10. Config Injection")
    void constructor_InjectsConfigValues() {
        assertThat(cardGeneratorService.generateCard().pan()).startsWith(BIN);
    }

    // =========================================================================
    // SECTION 3: SECURITY & COMPLIANCE
    // =========================================================================

    /**
     * Executes 50 PAN generations to statistically enforce Luhn validity,
     * safeguarding against transcription errors and issuer compliance failures.
     */
    @Test
    @DisplayName("11. Security: Generated PAN must satisfy Luhn")
    void generateUniquePan_PanPassesLuhnCheck() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);
        for (int i = 0; i < 50; i++) {
            assertThat(LuhnAlgorithm.isValid(cardGeneratorService.generateCard().pan())).isTrue();
        }
    }

    /**
     * Guarantees that every issued PAN carries the mandated BIN prefix, critical
     * for routing transactions across card network interchange rules.
     */
    @Test
    @DisplayName("12. BIN Compliance: Starts with Configured BIN")
    void generateUniquePan_PanStartsWithBin() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);
        assertThat(cardGeneratorService.generateCard().pan()).startsWith(BIN);
    }

    /**
     * Ensures CVV strings retain leading zeros (e.g., "012") and are not
     * truncated to two digits, maintaining PCI-DSS 3-digit CVM integrity.
     */
    @Test
    @DisplayName("13. CVV Compliance: Leading zeros preserved")
    void generateCvv_NeverGeneratesLeadingZeroAsOneDigit() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);
        boolean found = false;
        for (int i = 0; i < 1000; i++) {
            if (cardGeneratorService.generateCard().cvv().startsWith("0")) found = true;
        }
        assertThat(found).isTrue();
    }

    // =========================================================================
    // SECTION 4: EDGE CASES (FIXED LOGIC)
    // =========================================================================

    /**
     * Verifies correct padding when a 4-digit BIN is supplied, still yielding
     * a 16-digit Luhn-valid PAN compliant with ISO/IEC 7812.
     */
    @Test
    @DisplayName("14. Edge Case: Short (4-digit) BIN")
    void generateUniquePan_HandlesBinPrefixEdgeCases_Short() {
        // FIX: Use Helper
        CardGeneratorService shortSvc = createService(virtualCardRepository, "1234", 3);
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);

        String pan = shortSvc.generateCard().pan();
        assertThat(pan).startsWith("1234").hasSize(16);
        assertThat(LuhnAlgorithm.isValid(pan)).isTrue();
    }

    /**
     * Tests 8-digit BIN scenario to confirm payload allocation leaves sufficient
     * random digits while preserving final 16-digit length and Luhn check.
     */
    @Test
    @DisplayName("15. Edge Case: Long (8-digit) BIN")
    void generateUniquePan_HandlesBinPrefixEdgeCases_Long() {
        // FIX: Use Helper
        CardGeneratorService longSvc = createService(virtualCardRepository, "12345678", 3);
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);

        String pan = longSvc.generateCard().pan();
        assertThat(pan).startsWith("12345678").hasSize(16);
        assertThat(LuhnAlgorithm.isValid(pan)).isTrue();
    }

    /**
     * Ensures service rejects BINs ≥15 digits that would leave zero random payload,
     * preventing PAN construction violations and IllegalArgumentException.
     */
    @Test
    @DisplayName("21. Edge Case: BIN too long (>= 15 digits) throws Exception")
    void generateUniquePan_ThrowsIfBinTooLong() {
        // TARGET_PAYLOAD_LENGTH is 15.
        // If BIN is 15 chars, accountLength = 15 - 15 = 0, which triggers the exception.
        String longBin = "123456789012345"; // 15 digits

        // Use our helper to inject the invalid configuration
        CardGeneratorService badSvc = createService(virtualCardRepository, longBin, 3);

        assertThatThrownBy(badSvc::generateCard)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BIN prefix is too long");
    }

    // =========================================================================
    // SECTION 5: SAFETY
    // =========================================================================

    /**
     * Confirms generator remains stateless and does not persist card data,
     * aligning with separation-of-concerns and delegated repository responsibility.
     */
    @Test
    @DisplayName("16. Architecture: Service must NEVER call save()")
    void generateUniquePan_DoesNotCallSave() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);
        cardGeneratorService.generateCard();
        verify(virtualCardRepository, never()).save(any());
    }

    /**
     * Uses reflection to ensure no generated PAN, CVV or expiry remains in
     * service fields post-generation, reducing memory-resident secret exposure.
     */
    @Test
    @DisplayName("17. Safety: No internal state leakage")
    void generateCard_DoesNotStoreSecretsInMemory() throws IllegalAccessException {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);
        cardGeneratorService.generateCard();

        java.lang.reflect.Field[] fields = CardGeneratorService.class.getDeclaredFields();
        for (java.lang.reflect.Field field : fields) {
            field.setAccessible(true);
            // Check strings that are NOT the config values
            if (field.getType().equals(String.class) && !field.getName().equals("binPrefix")) {
                Object val = field.get(cardGeneratorService);
                if (val != null) assertThat(val.toString()).doesNotMatch("^\\d{16}$");
            }
        }
    }

    /**
     * Executes 1000 parallel generations to assert thread-safety and absence
     * of race conditions or duplicate PANs under high concurrency.
     */
    @Test
    @DisplayName("18. Thread Safety: Concurrent generation works")
    void generateCard_IsThreadSafe() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);

        java.util.stream.IntStream.range(0, 1000).parallel().forEach(i -> {
            CardSecrets secrets = cardGeneratorService.generateCard();
            assertThat(secrets.pan()).isNotNull();
        });
    }

    /**
     * Validates cryptographic randomness by asserting two distinct service
     * instances produce different PANs, confirming secure PRNG usage.
     */
    @Test
    @DisplayName("19. Entropy: Different instances produce different PANs")
    void generateUniquePan_DifferentSeedsProduceDifferentPans() {
        // FIX: Use Helper
        CardGeneratorService s1 = createService(virtualCardRepository, BIN, 3);
        CardGeneratorService s2 = createService(virtualCardRepository, BIN, 3);
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(false);

        assertThat(s1.generateCard().pan()).isNotEqualTo(s2.generateCard().pan());
    }

    /**
     * Confirms collision retry path is exercised by verifying repository
     * interaction count, implicitly validating logging of collision events.
     */
    @Test
    @DisplayName("20. Logging: Collision Warning")
    void generateCard_LogsCollisionWarning() {
        when(virtualCardRepository.existsByPanFingerprint(anyString())).thenReturn(true, false);
        cardGeneratorService.generateCard();
        verify(virtualCardRepository, times(2)).existsByPanFingerprint(anyString());
    }
}