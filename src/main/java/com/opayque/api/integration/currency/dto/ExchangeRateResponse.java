package com.opayque.api.integration.currency.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.opayque.api.integration.currency.CurrencyExchangeService;

import java.math.BigDecimal;
import java.util.Map;

/// Data Transfer Object (DTO) representing the schema of the external ExchangeRate-API response.
///
/// This record facilitates the deserialization of JSON payloads received from the
/// `[...](https://v6.exchangerate-api.com/v6/latest/){baseCode}` endpoint. It acts as the
/// initial landing zone for raw market data before it is validated and processed
/// by the [CurrencyExchangeService].
///
/// Implementation Details:
/// - **Immutability**: Leveraging Java Records ensures thread-safe, read-only data structures.
/// - **Financial Precision**: Uses [BigDecimal] for map values to align with the project's
///   mandate of zero rounding errors in financial math.
/// - **Naming Standards**: Maps snake_case JSON keys to camelCase Java fields using [JsonProperty].
///
/// @param result The status of the API request (e.g., "success" or "error").
/// @param baseCode The source ISO 4217 currency code used for the lookup.
/// @param conversionRates A map where keys are target ISO 4217 currency codes and
///                        values are the respective exchange rates relative to the baseCode.
public record ExchangeRateResponse(
        String result,

        @JsonProperty("base_code")
        String baseCode,

        @JsonProperty("conversion_rates")
        Map<String, BigDecimal> conversionRates
) {}