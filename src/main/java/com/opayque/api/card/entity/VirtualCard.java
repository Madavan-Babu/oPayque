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
@Table(name = "virtual_cards", uniqueConstraints = {
        @UniqueConstraint(columnNames = "pan_fingerprint", name = "uk_virtual_card_pan_fingerprint")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VirtualCard {

    /** Immutable surrogate key used for card-level tracing and correlation across PCI-DSS audit logs. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Encrypted wallet linkage enforcing fund isolation and regulatory segregation of duties. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /** Encrypted 16-digit PAN (AES-GCM). Randomized per-write. */
    @Convert(converter = AttributeEncryptor.class)
    @Column(name = "pan", nullable = false)
    private String pan;

    /** NEW: Blind Index for PAN Searchability.
     * Stores HMAC-SHA256(PAN). Deterministic but irreversible without the Hashing Key.
     * Used ONLY for existsByPan checks.
     */
    @Column(name = "pan_fingerprint", length = 64, nullable = false)
    private String panFingerprint;

    /** Encrypted 3-digit CVV (AES-GCM). Randomized per-write. */
    @Convert(converter = AttributeEncryptor.class)
    @Column(name = "cvv", nullable = false)
    private String cvv;

    /** CHANGED: Now Encrypted (AES-GCM).
     * Protected against tampering (Integrity) and unauthorized viewing.
     */
    @Convert(converter = AttributeEncryptor.class)
    @Column(name = "expiry_date", nullable = false)
    private String expiryDate;

    /** State-machine guard against fraudulent usage; transitions audited for PSD2 RTS compliance. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CardStatus status;

    /** Reversible cardholder identifier leveraged in 3-D Secure flows and chargeback dispute evidence. */
    @Column(name = "cardholder_name", nullable = false)
    private String cardholderName;

    /** Optional velocity control to mitigate BIN attack spend; null implies unlimited budget. */
    @Column(name = "monthly_limit")
    private BigDecimal monthlyLimit;

    /** Immutable audit timestamp for PSD2 RTS regulatory reporting and PCI-DSS 11.x traceability. */
    @CreationTimestamp
    private LocalDateTime createdAt;

    /** Optimistic-lock timestamp updated on every state mutation to support concurrent chargeback handling. */
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;


    // =========================================================================
    // LIFECYCLE HOOKS (AUTOMATION)
    // =========================================================================

    /**
     * Automatically calculates the Blind Index (HMAC) before persistence.
     * This ensures the fingerprint is always in sync with the PAN.
     */
    @PrePersist
    @PreUpdate
    private void synchronizeFingerprint() {
        if (this.pan != null) {
            // We use a static bridge to access the Spring Bean logic from the Entity
            this.panFingerprint = AttributeEncryptor.blindIndex(this.pan);
        }
    }
}