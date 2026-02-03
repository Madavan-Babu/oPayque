package com.opayque.api.infrastructure.config;

import net.jqwik.api.Example;
import org.joda.money.BigMoney;
import org.joda.money.CurrencyUnit;
import org.joda.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

/// Epic 2: Multi-Currency Account Management - Financial Infrastructure Validation.
///
/// This suite serves as a "Sanity Check" for the ["Joda-Money"] integration.
/// It demonstrates the mathematical necessity of using high-precision types over
/// standard floating-point or fixed-scale money types to prevent rounding errors
/// during complex financial calculations like exchange rates or fee applications.
class FinInfraSanityTest {

    /// Risk Audit: Standard Money Truncation.
    ///
    /// Demonstrates the "Precision Trap" where standard [Money] objects force
    /// compliance with ISO 4217 scales (e.g., 2 decimals for USD), leading to
    /// potential data loss.
    ///
    /// This test proves why [BigMoney] is the mandated solution for the oPayque
    /// ledger, as it preserves arbitrary scale to match the database schema of
    /// DECIMAL(19, 4).
    @Test
    @DisplayName("Risk Check: Standard Money truncates decimals (The Trap)")
    void showWhyWeNeedBigMoney() {
        // SCENARIO: Storing a high-precision exchange rate or interest fee.
        BigDecimal rawAmount = new BigDecimal("100.1234");

        // 1. Standard Money forces truncation to the ISO decimal limit (USD = 2).
        // This confirms that standard Java money types lose data (100.1234 -> 100.12).
        Money standardMoney = Money.of(CurrencyUnit.USD, rawAmount, java.math.RoundingMode.DOWN);

        assertThat(standardMoney.getAmount()).isNotEqualTo(rawAmount);

        // 2. BigMoney preserves the input scale (The Required Solution).
        // Aligns with the professional banking mandate for zero-error calculations.
        BigMoney highPrecisionMoney = BigMoney.of(CurrencyUnit.USD, rawAmount);

        assertThat(highPrecisionMoney.getAmount()).isEqualTo(rawAmount);
    }

    /// Verification of property-based testing infrastructure.
    ///
    /// Ensures that the JQwik engine is properly integrated into the Maven lifecycle
    /// for future edge-case discovery in the transaction engine.
    @Example
    void jqwik_ShouldBeEnabled() {
        assertThat(true).isTrue();
    }
}