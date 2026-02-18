package com.opayque.api.admin.dto;

import com.opayque.api.wallet.entity.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record AccountStatusUpdateRequest(
        @NotNull(message = "Status is required")
        AccountStatus status
) {}