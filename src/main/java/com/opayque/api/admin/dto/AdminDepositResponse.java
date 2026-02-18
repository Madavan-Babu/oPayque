package com.opayque.api.admin.dto;

import com.opayque.api.wallet.entity.LedgerEntry;
import com.opayque.api.wallet.entity.TransactionType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

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