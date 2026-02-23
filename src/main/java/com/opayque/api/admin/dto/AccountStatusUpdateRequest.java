package com.opayque.api.admin.dto;

import com.opayque.api.admin.controller.AdminWalletController;
import com.opayque.api.admin.service.AdminWalletService;
import com.opayque.api.wallet.entity.AccountStatus;
import com.opayque.api.wallet.repository.AccountRepository;
import jakarta.validation.constraints.NotNull;

/**
 * Immutable payload for administrative changes to an account's lifecycle state.
 * <p>
 * This DTO is consumed by the admin API (e.g. {@link AdminWalletController})
 * to request a transition of an {@link com.opayque.api.wallet.entity.Account}
 * from its current {@link AccountStatus} to a
 * new target status. The request is validated to ensure a status value is
 * provided ({@code @NotNull}) and that the requested transition conforms
 * to the business rules defined in {@link AccountStatus#canTransitionTo}.
 * </p>
 *
 * @param status the desired {@link AccountStatus} to which the account should transition.
 *
 * @see AdminWalletController
 * @see AdminWalletService
 * @see AccountRepository
 *
 * @author Madavan Babu
 * @since 2026
 */
public record AccountStatusUpdateRequest(
        @NotNull(message = "Status is required")
        AccountStatus status
) {}