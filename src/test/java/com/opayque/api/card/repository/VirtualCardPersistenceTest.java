package com.opayque.api.card.repository;

import com.opayque.api.card.entity.CardStatus;
import com.opayque.api.card.entity.VirtualCard;
import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.AccountRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;



/**
 * Story 4.1 – Vault-Integrated Encryption Verification.
 * <p>
 * Ensures that Virtual Card sensitive data (PAN/CVV) is encrypted at rest
 * in accordance with PCI-DSS mandates while remaining transparently
 * decrypted for in-memory processing.
 * <p>
 * @author Madavan Babu
 * @since 2026
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class VirtualCardPersistenceTest {

    @Autowired private VirtualCardRepository virtualCardRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager; // Critical for cache clearing

    /**
     * Validates end-to-end encryption hygiene: ciphertext in the persistence layer
     * and plaintext in the application layer.
     * <p>
     * This test simulates a malicious actor with raw DB access (hacker view) to
     * confirm that no cleartext PAN/CVV is exposed on disk while simultaneously
     * asserting that legitimate application code (app view) receives fully
     * decrypted data for downstream FinTech operations.
     */
    @Test
    @DisplayName("Hacker View: DB should contain ciphertext, Entity should contain plaintext")
    void shouldEncryptInDbAndDecryptInEntity() {
    // 1. Arrange: Set up the "Parent" entities (User + Wallet)
    User user =
        User.builder()
            .email("vault-test@opayque.com")
            .fullName("Vault Tester")
            .password("hashed_pw")
            .role(Role.CUSTOMER)
            .build();
        userRepository.save(user);

        Account wallet = Account.builder()
                .user(user)
                .currencyCode("USD")
                .iban("US99VAULT000000001")
                .build();
        accountRepository.save(wallet);

        // 2. Act: Save a Sensitive Card
        String plainPan = "4000123456789999";
        String plainCvv = "987";

        VirtualCard card = VirtualCard.builder()
                .account(wallet)
                .pan(plainPan)
                .cvv(plainCvv)
                .expiryDate("12/30")
                .cardholderName("Vault Tester")
                .status(CardStatus.ACTIVE)
                .monthlyLimit(new BigDecimal("1000.00"))
                .build();

        VirtualCard savedCard = virtualCardRepository.save(card);
        UUID cardId = savedCard.getId();

        // FLUSH & CLEAR: Force Hibernate to write to DB and forget the object in RAM.
        // This ensures the next fetch actually hits the database/encryptor.
        entityManager.flush();
        entityManager.clear();

        // 3. Assert (The Hacker View): Query via Raw SQL (JDBC)
        // If a hacker runs "SELECT * FROM virtual_cards", what do they see?
        String rawPan = jdbcTemplate.queryForObject(
                "SELECT pan FROM virtual_cards WHERE id = ?",
                String.class,
                cardId
        );
        String rawCvv = jdbcTemplate.queryForObject(
                "SELECT cvv FROM virtual_cards WHERE id = ?",
                String.class,
                cardId
        );

        // IT MUST BE ENCRYPTED (Gibberish)
        assertThat(rawPan).isNotNull();
        assertThat(rawPan).isNotEqualTo(plainPan); // "4000..." != "U2Fsd..."
        assertThat(rawPan).doesNotContain("4000"); // Ensure no partial leaks

        assertThat(rawCvv).isNotNull();
        assertThat(rawCvv).isNotEqualTo(plainCvv);

        // 4. Assert (The App View): Fetch via JPA Repository
        // The app should automatically decrypt it.
        VirtualCard fetchedCard = virtualCardRepository.findById(cardId).orElseThrow();

        assertThat(fetchedCard.getPan()).isEqualTo(plainPan);
        assertThat(fetchedCard.getCvv()).isEqualTo(plainCvv);
    }
}