package com.opayque.api.admin.dto;

import com.opayque.api.infrastructure.util.Masked;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.entity.AccountStatus;

import java.util.UUID;

public record AdminAccountResponse(
        UUID id,
        @Masked
        String iban,
        String currency,
        AccountStatus status,
        @Masked
        String ownerEmail
) {
    // Static Mapper for convenience
    public static AdminAccountResponse fromEntity(Account account) {
        return new AdminAccountResponse(
                account.getId(),
                account.getIban(),
                account.getCurrencyCode(),
                account.getStatus(),
                account.getUser() != null ? account.getUser().getEmail() : null
        );
    }
}