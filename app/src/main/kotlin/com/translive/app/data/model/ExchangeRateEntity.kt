package com.translive.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exchange_rates")
data class ExchangeRateEntity(
    @PrimaryKey val currencyCode: String, // e.g. "EUR", "RUB", "VND"
    val rateToUsd: Double,               // 1 USD = rateToUsd * Currency
    val lastUpdatedMillis: Long = System.currentTimeMillis()
)
