package com.opayque.api.wallet.dto;

import com.opayque.api.wallet.validation.ValidCurrency;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/// Multi-Currency Account Management - Provisioning Request Schema.
///
/// Encapsulates the mandatory parameters required to open a new digital sub-wallet.
/// Utilizes Bean Validation to ensure all incoming data conforms to ISO standards
/// before entering the business layer.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateAccountRequest {

    /// The target ISO 4217 currency code for the new wallet.
    /// Validated by @ValidCurrency to ensure the requested currency is supported
    /// by the oPayque "Opaque" core engine.
    @NotNull(message = "Currency code is required")
    @ValidCurrency
    private String currencyCode;
}