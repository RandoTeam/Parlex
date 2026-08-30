package com.translive.app.data

/**
 * Embedded offline baseline exchange rate table (relative to USD = 1.0)
 * for 35+ major currencies to guarantee zero-network instant conversion on any install.
 */
object ExchangeRateBaseline {

    val RATES_TO_USD: Map<String, Double> = mapOf(
        "USD" to 1.0,
        "EUR" to 0.92,
        "RUB" to 91.5,
        "CNY" to 7.23,
        "VND" to 25400.0,
        "GBP" to 0.79,
        "JPY" to 155.0,
        "KRW" to 1380.0,
        "TRY" to 34.2,
        "KZT" to 480.0,
        "AED" to 3.67,
        "THB" to 36.5,
        "IDR" to 16250.0,
        "INR" to 83.5,
        "BRL" to 5.45,
        "CAD" to 1.37,
        "AUD" to 1.51,
        "CHF" to 0.89,
        "SGD" to 1.35,
        "MYR" to 4.70,
        "PHP" to 58.5,
        "ILS" to 3.75,
        "SAR" to 3.75,
        "EGP" to 48.5,
        "PLN" to 3.95,
        "CZK" to 23.2,
        "HUF" to 365.0,
        "BGN" to 1.80,
        "DKK" to 6.87,
        "NOK" to 10.65,
        "SEK" to 10.55,
        "GEL" to 2.70,
        "AMD" to 387.0,
        "UZS" to 12650.0,
        "BYN" to 3.27
    )
}
