package com.opayque.api.admin.dto;

import com.opayque.api.admin.controller.AdminWalletController;
import com.opayque.api.admin.service.AdminWalletService;
import com.opayque.api.wallet.repository.AccountRepository;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * <p>This record serves as the Data Transfer Object (DTO) that encapsulates the
 * payload required by the deposit endpoint of the wallet API. It is consumed
 * by controller layer methods (e.g. {@link AdminWalletController}) to request
 * the crediting of a specified {@code amount} to a user’s account in the given
 * {@code currency}.</p>
 *
 * <p>The DTO is intentionally immutable and validated at the boundary using
 * Jakarta Bean Validation annotations. The fields mirror the business rules
 * governing monetary deposits:</p>
 *
 * <ul>
 *   <li>{@code amount} – the monetary value to be deposited.
 *       Must be non‑null, positive, and conform to a {@code 10,2} precision
 *       ({@link BigDecimal}) as enforced by {@code @NotNull},
 *       {@code @Positive} and {@code @Digits}.</li>
 *   <li>{@code currency} – ISO‑4217 three‑letter currency code (e.g. {@code EUR},
 *       {@code GBP}). It is required and must match the regular expression
 *       {@code "[A-Z]{3}"} as validated by {@code @NotBlank} and {@code @Pattern}.</li>
 *   <li>{@code description} – an optional textual note describing the deposit.
 *       Limited to a maximum of 100 characters, enforced by {@code @Size}.</li>
 * </ul>
 *
 * <p>Although this DTO is not a JPA entity and therefore has no direct table
 * mapping, it represents the input contract that ultimately results in a
 * {@link com.opayque.api.wallet.entity.LedgerEntry} being persisted by the
 * service and repository layers.</p>
 *
 * @see AdminWalletController
 * @see AdminWalletService
 * @see AccountRepository
 *
 * @author Madavan Babu
 * @since 2026
 */
public record MoneyDepositRequest(

        @NotNull(message = "Amount is required")
        @Positive(message = "Deposit amount must be greater than zero")
        @Digits(integer = 10, fraction = 2, message = "Amount cannot exceed 10 digits with 2 decimal places")
        BigDecimal amount,

        @NotBlank(message = "Currency is required")
        @Pattern(regexp = "[A-Z]{3}", message = "Currency must be a valid 3-letter ISO code (e.g., EUR, GBP)")
        String currency,

        @Size(max = 100, message = "Description cannot exceed 100 characters")
        String description
) { }