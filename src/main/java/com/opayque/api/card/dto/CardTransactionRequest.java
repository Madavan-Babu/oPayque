package com.opayque.api.card.dto;

import com.opayque.api.wallet.validation.ValidCurrency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Data Transfer Object representing a simulated card‑network transaction request.
 *
 * <p>This payload is used by the external transaction simulation flow (see {@link
 * com.opayque.api.card.service.CardTransactionService}) to emulate the message a payment processor
 * such as Stripe, Visa or Mastercard would forward to oPayque's issuer component. The structure
 * mirrors key elements of an ISO‑8583 request while remaining JSON‑serialisable for REST endpoints.
 *
 * <p>Each field is guarded by Bean Validation annotations that enforce the contract expected by
 * downstream services:
 *
 * <ul>
 *   <li>{@code pan} – 16‑digit Primary Account Number; validated with
 *       {@code @Pattern("^\\d{16}$")}.
 *   <li>{@code cvv} – 3‑digit Card Verification Value; {@code @Pattern("^\\d{3}$")}.
 *   <li>{@code expiryDate} – Expiration in {@code MM/YY} format;
 *       {@code @Pattern("^(0[1-9]|1[0-2])/\\d{2}$")}.
 *   <li>{@code amount} – Transaction amount; must be non‑null and {@code >= 0.01}.
 *   <li>{@code currency} – ISO‑4217 currency code; validated by the custom {@code @ValidCurrency}
 *       constraint.
 *   <li>{@code merchantName} – Human‑readable merchant identifier; non‑blank.
 *   <li>{@code merchantCategoryCode} – Optional 4‑digit MCC; {@code @Pattern("^\\d{4}$")} when
 *       supplied.
 *   <li>{@code externalTransactionId} – Acquirer‑provided unique identifier; used for idempotency.
 * </ul>
 *
 * <p>The DTO is immutable when constructed via the Lombok {@code @Builder} and is serialised
 * directly to JSON by Spring MVC controllers handling {@code /cards/transactions} endpoints.
 *
 * @author Madavan Babu
 * @since 2026
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardTransactionRequest {

    /**
     * The 16-digit Primary Account Number (Cleartext).
     * Simulates the data read from the magnetic stripe or EMV chip.
     */
    @NotBlank(message = "PAN is required")
    @Pattern(regexp = "^\\d{16}$", message = "PAN must be exactly 16 digits")
    private String pan;

    /**
     * Card Verification Value (CVV2).
     */
    @NotBlank(message = "CVV is required")
    @Pattern(regexp = "^\\d{3}$", message = "CVV must be exactly 3 digits")
    private String cvv;

    /**
     * Expiration Date in MM/YY format.
     */
    @NotBlank(message = "Expiry Date is required")
    @Pattern(regexp = "^(0[1-9]|1[0-2])/\\d{2}$", message = "Expiry must be in MM/YY format")
    private String expiryDate;

    /**
     * Transaction Amount in the transaction currency.
     */
    @NotNull(message = "Amount is required")
    @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
    private BigDecimal amount;

    /**
     * ISO 4217 Currency Code (e.g., EUR, GBP).
     */
    @NotBlank(message = "Currency is required")
    @ValidCurrency // Reusing your existing validator
    private String currency;

    /**
     * Name of the merchant (Card Acceptor Name).
     */
    @NotBlank(message = "Merchant Name is required")
    private String merchantName;

    /**
     * ISO 18245 Merchant Category Code (4 digits).
     * Optional for now, but good to have for future categorization logic.
     */
    @Pattern(regexp = "^\\d{4}$", message = "MCC must be 4 digits")
    private String merchantCategoryCode;

    /**
     * Unique identifier from the Acquirer/Network.
     * Critical for Idempotency keys.
     */
    @NotBlank(message = "External Transaction ID is required")
    private String externalTransactionId;
}