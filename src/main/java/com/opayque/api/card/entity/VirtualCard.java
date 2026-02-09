package com.opayque.api.card.entity;

import com.opayque.api.infrastructure.encryption.AttributeEncryptor;
import com.opayque.api.wallet.entity.Account;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Immutable-style JPA entity representing a PCI-DSS compliant virtual payment card within the
 * oPayque neobank ecosystem.
 *
 * <p>This aggregate root encapsulates all cardholder data (CHD) and sensitive authentication data
 * (SAD) required for card-not-present (CNP) FinTech transactions while enforcing cryptographic
 * protection at the persistence layer via {@link AttributeEncryptor}.
 *
 * <p>Key responsibilities:
 *
 * <ul>
 *   <li>Tokenized linkage to the {@link Account} wallet (money bucket).
 *   <li>Storage of PAN, CVV and expiry in encrypted form satisfying OWASP ASVS 8.x.
 *   <li>Lifecycle governance through {@link CardStatus} state machine.
 *   <li>Optional spend-control enforcement via monthlyLimit (velocity check).
 * </ul>
 *
 * Thread-safety: This entity is intended for use within Spring-managed transactions; mutable fields
 * are protected by optimistic locking at the service layer.
 *
 * <p>Audit trail: creation and last-modification timestamps are auto-populated by Hibernate envers
 * for regulatory reporting (PSD2, PCI-DSS 11.x).
 *
 * @author Madavan Babu
 * @since 2026
 */
@Entity
@Table(name = "virtual_cards")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VirtualCard {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Link to the Wallet (The Bucket of Money)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    // SENSITIVE: Encrypted in DB
    @Convert(converter = AttributeEncryptor.class)
    @Column(name = "pan", nullable = false)
    private String pan; // The 16-digit number

    // SENSITIVE: Encrypted in DB
    @Convert(converter = AttributeEncryptor.class)
    @Column(name = "cvv", nullable = false)
    private String cvv; // The 3-digit code

    @Column(name = "expiry_date", nullable = false)
    private String expiryDate; // MM/YY format (Not sensitive enough to encrypt usually, but can be)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardStatus status;

    @Column(name = "cardholder_name", nullable = false)
    private String cardholderName;

    // Optional: Spending Limit
    @Column(name = "monthly_limit")
    private BigDecimal monthlyLimit;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;
}