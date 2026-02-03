package com.opayque.api.wallet.dto;

import java.math.BigDecimal;

/// The "Dashboard" View DTO.
///
/// Represents a consolidated view of a wallet, combining its static metadata (Account details)
/// with its dynamic financial state (Real-time Balance).
///
/// @param account The structural details of the wallet (IBAN, Currency, et cetera).
/// @param balance The calculated available funds (Aggregated from Ledger).
public record WalletSummary(
        AccountResponse account,
        BigDecimal balance
) {}