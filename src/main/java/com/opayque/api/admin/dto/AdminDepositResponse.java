package com.opayque.api.admin.dto;

import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * <p>This record is a Data Transfer Object (DTO) that represents the payload returned by
 * the administrative deposit endpoint. It aggregates the essential details of a ledger
 * entry that resulted from an admin‑initiated deposit operation.</p>
 *
 * <p>Each field mirrors a column of the underlying {@link LedgerEntry} entity, allowing
 * the service layer to translate persistent state into a stable, version‑agnostic
 * response structure that can be safely exposed via REST APIs.</p>
 *
 * <p>Fields:</p>
 * <ul>
 *   <li>{@code transactionId} – the primary key of the {@link LedgerEntry}.</li>
 *   <li>{@code referenceId} – an external reference that may be used for audit or
 *       reconciliation purposes.</li>
 *   <li>{@code amount} – monetary value of the deposit.</li>
 *   <li>{@code currency} – ISO‑4217 currency code.</li>
 *   <li>{@code type} – {@link TransactionType} indicating the entry is a {@code CREDIT}
 *       for deposit operations.</li>
 *   <li>{@code status} – fixed to {@code "CLEARED"} because admin deposit entries are
 *       final and immutable once recorded.</li>
 *   <li>{@code timestamp} – the moment the ledger entry was persisted.</li>
 * </ul>
 *
 * <p>The static factory method {@code fromEntity} enables a concise conversion from a
 * {@link LedgerEntry} JPA entity to this response record, ensuring that only the
 * relevant data is exposed to API consumers.</p>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see LedgerEntry
 * @see TransactionType
 */
public record AdminDepositResponse(
        UUID transactionId,
        UUID referenceId,
        BigDecimal amount,
        String currency,
        TransactionType type,
        String status,
        LocalDateTime timestamp
) {
    public static AdminDepositResponse fromEntity(LedgerEntry entry) {
        return new AdminDepositResponse(
                entry.getId(),
                entry.getReferenceId(),
                entry.getAmount(),
                entry.getCurrency(),
                entry.getTransactionType(),
                "CLEARED", // Ledger entries are final
                entry.getRecordedAt()
        );
    }
}