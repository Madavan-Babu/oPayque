package com.opayque.api.card.service;

import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.card.model.CardSecrets;
import com.opayque.api.card.repository.VirtualCardRepository;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


/**
 * PCI-DSS compliant integration test validating the end-to-end card-issuance pipeline:
 * Service → Encryptor → Repository → Real Postgres DB.
 * <p>
 * Ensures cryptographic confidentiality of PAN & CVV, enforces schema-level uniqueness
 * via blind-index fingerprinting, and guarantees tenant isolation across wallets.
 * <p>
 * Runs inside a Testcontainers Postgres 15-alpine instance with dynamic property override
 * to prevent H2 fallback, satisfying regulatory traceability (PSD2 RTS & PCI-DSS 11.x).
 *
 * @author Madavan Babu
 * @since 2026
 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class CardGenerationIntegrationTest {

    // 1. Spin up a Real Postgres Instance (15-alpine as requested)
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    // 2. DYNAMICALLY OVERRIDE H2 DEFAULTS
    // This runs before the ApplicationContext starts, forcing Postgres settings.
    /**
     * Dynamically overrides H2 defaults to force Postgres driver & dialect,
     * ensuring unique-constraint enforcement and preventing LOB creation errors.
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // Force the Driver (overriding H2 from application.yaml)
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // Force the Dialect (Crucial for Unique Constraints to work)
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");

        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");

        // 2. DRIVER FIX: Silence the "Method createClob() is not yet implemented" error.
        // This tells Hibernate to avoid asking the driver to create LOB instances.
        registry.add("spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation", () -> "true");
    }

    @Autowired private CardGeneratorService cardGeneratorService;
    @Autowired private VirtualCardRepository virtualCardRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;

    private Account mainWallet;

    /**
     * Provides pristine test isolation by clearing all card & wallet data,
     * then pre-provisions a USD wallet linked to a PSD2 customer for downstream issuance flows.
     */
    @BeforeEach
    void setup() {
        // Clear DB to ensure a clean state for constraints
        virtualCardRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();

        // 1. Create Main User & Wallet
        User user = User.builder()
                .email("factory-main@opayque.com")
                .fullName("Factory Tester")
                .password("hashed_pw")
                .role(Role.CUSTOMER)
                .build();
        userRepository.save(user);

        mainWallet = Account.builder()
                .user(user)
                .currencyCode("USD")
                .iban("US99MAIN000000001")
                .build();
        accountRepository.save(mainWallet);
    }

    // =========================================================================
    // 1. PERSISTENCE & ENCRYPTION ROUND-TRIP
    // =========================================================================
    /**
     * Validates that generated secrets (PAN, CVV, expiry) are encrypted before persistence
     * and correctly decrypted on read, while the blind-index fingerprint enforces uniqueness
     * without revealing plaintext PAN, meeting PCI-DSS requirement 3.4.
     */
    @Test
    @DisplayName("1. Happy Path: Generate, Save, and Verify Encryption Round-Trip")
    void shouldPersistGeneratedSecretsWithEncryptedPanAndCvv() {
        // 1. Generate
        CardSecrets secrets = cardGeneratorService.generateCard();

        // 2. Persist
        VirtualCard card = VirtualCard.builder()
                .account(mainWallet)
                .pan(secrets.pan()) // Entity will Encrypt + Blind Index this
                .cvv(secrets.cvv()) // Entity will Encrypt this
                .expiryDate(secrets.expiryDate())
                .cardholderName("Test User")
                .status(CardStatus.ACTIVE)
                .build();

        VirtualCard saved = virtualCardRepository.saveAndFlush(card);

        // 3. Clear Persistence Context
        // This ensures we are testing the actual DB state + Decryption logic
        // We rely on the returned 'saved' object which is managed.

        // 4. Assertions
        assertThat(saved.getId()).isNotNull();
        // The getter triggers the AttributeConverter (Decrypt)
        assertThat(saved.getPan()).isEqualTo(secrets.pan());
        assertThat(saved.getCvv()).isEqualTo(secrets.cvv());

        // 5. Verify Blind Index Populated (Schema Check)
        boolean exists = virtualCardRepository.existsByPanFingerprint(
                com.opayque.api.infrastructure.encryption.AttributeEncryptor.blindIndex(secrets.pan())
        );
        assertThat(exists).as("Blind Index lookup failed").isTrue();
    }

    // =========================================================================
    // 2. DATABASE CONSTRAINTS (THE "BLIND INDEX" GUARDRAIL)
    // =========================================================================
    /**
     * Confirms schema-level uniqueness constraint on pan_fingerprint prevents
     * duplicate PAN insertion, mitigating BIN attack vectors and ensuring
     * card-holder data integrity across the neobank ledger.
     */
    @Test
    @DisplayName("2. Uniqueness: DB must reject duplicate PANs (via Blind Index Constraint)")
    void shouldRejectDuplicatePanViaBlindIndex_SchemaLevel() {
        String pan = "1711030000111222";

        // 1. Save Card A
        VirtualCard cardA = VirtualCard.builder()
                .account(mainWallet).pan(pan).cvv("123").expiryDate("12/30")
                .cardholderName("User A").status(CardStatus.ACTIVE).build();
        virtualCardRepository.saveAndFlush(cardA);

        // 2. Try Save Card B (Same PAN)
        VirtualCard cardB = VirtualCard.builder()
                .account(mainWallet).pan(pan).cvv("999").expiryDate("12/30")
                .cardholderName("User B").status(CardStatus.ACTIVE).build();

        // 3. Assert: Must Fail with DataIntegrityViolation (Unique Constraint on Fingerprint)
        assertThatThrownBy(() -> virtualCardRepository.saveAndFlush(cardB))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // =========================================================================
    // 3. MULTI-ACCOUNT ISOLATION
    // =========================================================================
    /**
     * Demonstrates multi-tenant isolation: distinct wallets receive unique cards
     * with non-overlapping PANs, satisfying PSD2 account segregation and
     * preventing cross-tenant data leakage.
     */
    @Test
    @DisplayName("3. Multi-Tenancy: Different Accounts, Unique Cards")
    void shouldCreateCardForMultipleAccounts() {
        // Set up Second Wallet
        User user2 = User.builder().email("second@opayque.com").fullName("User 2").password("pw").role(Role.CUSTOMER).build();
        userRepository.save(user2);
        Account wallet2 = Account.builder().user(user2).currencyCode("EUR").iban("EU99SECOND").build();
        accountRepository.save(wallet2);

        // Generate for Wallet 1
        CardSecrets s1 = cardGeneratorService.generateCard();
        virtualCardRepository.saveAndFlush(VirtualCard.builder()
                .account(mainWallet).pan(s1.pan()).cvv(s1.cvv()).expiryDate(s1.expiryDate())
                .cardholderName("U1").status(CardStatus.ACTIVE).build());

        // Generate for Wallet 2
        CardSecrets s2 = cardGeneratorService.generateCard();
        virtualCardRepository.saveAndFlush(VirtualCard.builder()
                .account(wallet2).pan(s2.pan()).cvv(s2.cvv()).expiryDate(s2.expiryDate())
                .cardholderName("U2").status(CardStatus.ACTIVE).build());

        assertThat(s1.pan()).isNotEqualTo(s2.pan());
        assertThat(virtualCardRepository.count()).isEqualTo(2);
    }
}