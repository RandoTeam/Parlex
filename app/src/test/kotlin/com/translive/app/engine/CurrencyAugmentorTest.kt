package com.translive.app.engine

import com.translive.app.data.ExchangeRateRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.db.ExchangeRateDao
import com.translive.app.data.model.Currency
import com.translive.app.data.model.ExchangeRateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CurrencyAugmentorTest {

    private val fakeDao = object : ExchangeRateDao {
        override suspend fun getAllRatesList(): List<ExchangeRateEntity> = emptyList()
        override fun getAllRates(): Flow<List<ExchangeRateEntity>> = flowOf(emptyList())
        override suspend fun getRate(code: String): ExchangeRateEntity? = null
        override suspend fun insertAll(rates: List<ExchangeRateEntity>) {}
        override suspend fun clearAll() {}
    }

    private val fakeSettings = object : SettingsRepository() {
        override var enableCurrencyConversion = true
        override var homeCurrencyCode = "RUB"
    }

    @Test
    fun `augment appends converted home currency in parentheses`() = runBlocking {
        val repo = ExchangeRateRepository(fakeDao, fakeSettings)
        val augmentor = CurrencyAugmentor(repo, fakeSettings)

        // $50 -> converted to RUB based on baseline rate (~91.5 RUB/USD)
        val augmented = augmentor.augment("The ticket costs $50 in total.", forcedHomeCurrency = Currency.RUB)
        assertTrue(augmented.contains("≈") && augmented.contains("₽"))
    }

    @Test
    fun `augment skips conversion when detected currency matches home currency`() = runBlocking {
        val usdSettings = object : SettingsRepository() {
            override var enableCurrencyConversion = true
            override var homeCurrencyCode = "USD"
        }
        val repo = ExchangeRateRepository(fakeDao, usdSettings)
        val augmentor = CurrencyAugmentor(repo, usdSettings)

        val input = "The ticket costs $50 in total."
        val augmented = augmentor.augment(input, forcedHomeCurrency = Currency.USD)
        assertEquals(input, augmented)
    }
}
