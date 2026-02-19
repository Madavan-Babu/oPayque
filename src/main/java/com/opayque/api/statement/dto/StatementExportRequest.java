package com.opayque.api.statement.dto;

import com.opayque.api.statement.controller.StatementController;
import com.opayque.api.statement.service.StatementService;
import com.opayque.api.wallet.entity.Account;
import com.opayque.api.wallet.repository.LedgerRepository;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import lombok.Builder;

import java.time.LocalDate;
import java.util.UUID;

/**
 * <p>This record represents a request to export the statement of a specific
 * account within a defined date range.</p>
 *
 * <p>It is typically used by the {@link StatementController} to receive
 * client parameters, forwarded to {@link StatementService} for
 * processing, and may involve persistence checks via
 * {@link com.opayque.api.wallet.repository.LedgerRepository}.</p>
 *
 * <p><strong>Fields</strong></p>
 * <ul>
 *   <li>{@code accountId} – The unique identifier of the {@link Account}
 *       for which the statement is being requested. Must not be {@code null}.</li>
 *   <li>{@code startDate} – The inclusive start date of the period. Must be
 *       {@code non‑null} and may not be set in the future.</li>
 *   <li>{@code endDate} – The inclusive end date of the period. Must be
 *       {@code non‑null} and may be equal to or later than {@code startDate}.</li>
 * </ul>
 *
 * @see StatementController
 * @see StatementService
 *
 * @author Madavan Babu
 * @since 2026
 */
@Builder
public record StatementExportRequest(

        @NotNull(message = "Account ID is mandatory")
        UUID accountId,

        @NotNull(message = "Start date is mandatory")
        @PastOrPresent(message = "Start date cannot be in the future")
        LocalDate startDate,

        @NotNull(message = "End date is mandatory")
        LocalDate endDate
) {
    /**
     * <p>Validates the date interval of a {@link StatementExportRequest}.
     * It ensures that the {@code startDate} is not later than the {@code endDate}
     * and that the total span does not exceed the permitted {@code maxRangeMonths}.
     * </p>
     *
     * <p>This check prevents the {@link StatementService} from handling
     * excessively large export ranges that could degrade performance or
     * expose the system to denial‑of‑service risks.</p>
     *
     * @param maxRangeMonths the maximum number of months allowed between
     *                       {@code startDate} and {@code endDate}. Must be a
     *                       positive integer.
     *
     * @see StatementController
     * @see StatementService
     * @see LedgerRepository
     */
    public void validateDateRange(int maxRangeMonths) {
        if (startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("Start date cannot be after end date.");
        }
        if (startDate.isBefore(endDate.minusMonths(maxRangeMonths))) {
            throw new IllegalArgumentException("Statement export range cannot exceed " + maxRangeMonths + " months.");
        }
    }
}