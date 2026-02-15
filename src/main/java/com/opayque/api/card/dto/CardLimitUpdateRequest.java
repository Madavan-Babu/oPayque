package com.opayque.api.card.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Immutable request to modify the spending ceiling of a virtual card.
 * <p>
 * validated strictly to prevent negative limits or null payloads.
 */
public record CardLimitUpdateRequest(
        @NotNull(message = "New limit is required")
        @DecimalMin(value = "0.00", message = "Limit cannot be negative")
        BigDecimal newLimit
) {}