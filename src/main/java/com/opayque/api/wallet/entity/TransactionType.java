package com.opayque.api.wallet.entity;

/// Enumeration defining the fundamental classification of ledger entries in the oPayque engine.
///
/// This enum dictates the high-level movement of funds and is used by the transfer engine
/// to determine the accounting [LedgerEntry#direction]. It serves as a core
/// component of the "Magic Ledger," providing the semantic basis for all financial
/// record-keeping.
///
/// **Usage in Domain Logic:**
/// - **CREDIT**: Represents an inflow of capital (e.g., Deposit, Received Transfer).
/// - **DEBIT**: Represents an outflow of capital (e.g., Withdrawal, Sent Transfer).
///
/// This classification ensures adherence to ACID-compliant money movement by clearly
/// separating the source and destination of atomic transactions.
public enum TransactionType {

    /// Indicates an increase in the account's available balance (Money IN).
    /// Used for operations where the account is the recipient of funds.
    CREDIT,

    /// Indicates a decrease in the account's available balance (Money OUT).
    /// Used for operations where the account is the source of funds.
    DEBIT
}