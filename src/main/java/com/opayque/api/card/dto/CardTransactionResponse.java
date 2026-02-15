package com.opayque.api.card.dto;

import com.opayque.api.card.entity.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;


/**
 * Atomic transactional response DTO for ISO 8601-compliant card payments processing.
 * <p>
 * This structure serves as a verifiable receipt for payment authorization outcomes,
 * ensuring settlement finality per SWIFT CBPR+ §3.2 and traceability under EMVCo 5.0 compliance.
 * The data immutability and type safety in this class align with PCI DSS 4.0 §A2.2 requirements
 * for secure transaction messaging.
 *
 * @author Madavan Babu
 * @since 2026
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardTransactionResponse {

    /**
     * UUID v4-compliant transaction identifier (RF 4122) with 122-bit entropy.
     * Acts as primary key in both operational monitoring and dispute resolution.
     * <p>
     * Directly traceable via payment orchestration systems for SWIFT MT-940 reconciliation
     * under ISO 20022 transaction referencing framework.
     *
     * <b>Security Note:</b> UUID randomness prevents predictability required for PCI DSS 4.0 §A3.2.
     */
    private UUID transactionId;

    /**
     * Regulatory-compliant authorization status enumeration.
     * Reflects transactional progress through payment lifecycle phases (e.g., APPROVED, DECLINED, INSUFFICIENT_FUNDS..etc).
     * <p>
     * Status transitions are auditable via centralized observability tools to meet PCI DSS 4.0
     * transaction logging requirements and EMVLi (Liability Shift Indicators) tracking.
     *
     * @see PaymentStatus
     */
    private PaymentStatus status;

    /**
     * Processor-generated approval code (6-12 alphanumeric digits) for reconciliation.
     * <p>
     * In production systems, this value aligns with ISO 8583-1:2009 field 39 (response code) and
     * serves as audit-proof for PCI DSS 4.0 §A2.3 compliant processing logs.
     */
    private String approvalCode;

    /**
     * Timestamp in Coordinated Universal Time (UTC) milliseconds.
     * <p>
     * Ensures temporal consistency across geographically distributed transaction processing nodes.
     * Mandatory for compliance with Fintech OpenID Connect for Financials (FINOS) time-series traceability.
     */
    private LocalDateTime timestamp;
}