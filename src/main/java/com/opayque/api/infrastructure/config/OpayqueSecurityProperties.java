package com.opayque.api.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.Map;


/**
 * Externalised, version-aware cryptographic material for oPayque's PCI-DSS compliant card-data protection.
 *
 * <p>Supports key rotation without downtime by mapping sequential {@code versionId}s to AES-256-GCM
 * passphrase-derived keys. The {@code activeVersion} designates the key used for NEW encryption
 * (e.g., VirtualCard PAN/CVV) while older versions remain available for decryption during the
 * cryptoperiod overlap. The {@code hashingKey} is a 256-bit HMAC secret used exclusively for
 * blind-indexing PANs, enabling deterministic searches without revealing plaintext values.
 *
 * <p>YAML example:
 * <pre>
 * opayque:
 *   security:
 *     keys:
 *       1: "U2FsdGVkX1+..."
 *       2: "U2FsdGVkX1+..."
 *     active-version: 2
 *     hashing-key: "U2FsdGVkX1+..."
 * </pre>
 *
 * <p>Thread-safety: Properties are immutable post-binding; concurrent access is safe.
 *
 * @author Madavan Babu
 * @since 2026
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "opayque.security")
public class OpayqueSecurityProperties {

    /**
     * Versioned passphrase map for envelope encryption of cardholder data.
     *
     * <p>Each entry associates a monotonically increasing version identifier with a Base64-encoded
     * 32-byte secret used to derive the AES-256-GCM key via PBKDF2-HMAC-SHA256 (200k iterations).
     * Rotation is triggered by policy (e.g., quarterly) or security incident. Keys are never
     * reused across cryptoperiods and are securely wiped from heap after derivation.
     */
    private Map<Integer, String> keys;

    /**
     * Designates the {@link #keys} entry that MUST be used for all NEW encryption operations.
     *
     * <p>Must match an existing key version; otherwise startup fails fast. During rotation
     * this value is atomically updated via Spring Cloud Config Server without restarting
     * card-issuing microservices, ensuring zero-downtime key roll-over.
     */
    private Integer activeVersion;

    /**
     * 256-bit HMAC secret (Base64) for creating deterministic PAN fingerprints.
     *
     * <p>Used exclusively in {@link com.opayque.api.card.entity.VirtualCard}'s `panFingerprint` field to generate
     * HMAC-SHA256(PAN) blind indices. This allows PAN existence checks without
     * storing reversible data, satisfying PCI-DSS Req-3.4 and OWASP ASVS 8.3.
     * The key is rotation-independent and MUST be unique per environment.
     */
    private String hashingKey;
}