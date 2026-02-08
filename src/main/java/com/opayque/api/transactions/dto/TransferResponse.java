package com.opayque.api.transactions.dto;

import java.util.UUID;

/// **Story 3.3: The Transfer Receipt (Output)**.
///
/// Returns the immutable proof of a completed transaction.
/// Does not expose internal User IDs or Account balances (Opaque Security).
public record TransferResponse(
        UUID transferId,
        String status,
        String timestamp
) {}