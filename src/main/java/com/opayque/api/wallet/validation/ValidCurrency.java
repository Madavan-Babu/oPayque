package com.opayque.api.wallet.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.*;

/// Multi-Currency Account Management - ISO 4217 Validation Constraint.
///
/// Annotation utilized to enforce strict compliance with global financial currency standards.
/// It targets fields and parameters to ensure that incoming currency codes are
/// recognized and valid within the oPayque ecosystem.
///
/// Implementation: Delegates validation logic to the [CurrencyValidator] class.
@Documented
@Constraint(validatedBy = CurrencyValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCurrency {

    /// Default error message for invalid currency code violations.
    String message() default "Invalid ISO 4217 currency code";

    /// Supports the categorization of validation constraints into groups.
    Class<?>[] groups() default {};

    /// Utilized to carry metadata information used by validation clients.
    Class<? extends Payload>[] payload() default {};
}