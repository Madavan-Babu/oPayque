package com.opayque.api.integration.currency;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.Map;

// Maps the JSON from: https://v6.exchangerate-api.com/v6/KEY/latest/USD
public record ExchangeRateResponse(
        String result,
        @JsonProperty("base_code") String baseCode,
        @JsonProperty("conversion_rates") Map<String, BigDecimal> conversionRates
) {}