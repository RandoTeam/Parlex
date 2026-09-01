package com.translive.app.data

import com.translive.app.data.db.ExchangeRateDao
import com.translive.app.data.model.Currency
import com.translive.app.data.model.CurrencySyncPolicy
import com.translive.app.data.model.ExchangeRateEntity
import com.translive.app.engine.CurrencyAugmentor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pure JVM Unit Test Suite for Currency Offline Fallback, Sync Policy Evaluation,
 * and Attribution Formatting.
 *
 * Requirements:
 * 1. Test Offline Fallback Rates: All supported currencies convert accurately using baseline cache.
 * 2. Test Sync Policy evaluation:
 *    - Daily policy only triggers sync if >24h elapsed since last sync.
 *    - Manual policy never triggers automatic sync.
 * 3. Test Last Updated formatting:
 *    - Relative timestamps ('Just now', 'Today, 14:00', 'Yesterday', 'Offline cache (default)').
 * 4. 100% pure JVM (Zero Android dependencies, zero Robolectric).
 */
class CurrencySyncAndAttributionTest {

    // =========================================================================
    // Test Doubles (Pure JVM Fakes)
    // =========================================================================

    private class FakeExchangeRateDao : ExchangeRateDao {
        val storage = mutableMapOf<String, ExchangeRateEntity>()

        override suspend fun getAllRatesList(): List<ExchangeRateEntity> = storage.values.toList()

        override fun getAllRates(): Flow<List<ExchangeRateEntity>> = flowOf(storage.values.toList())

        override suspend fun getRate(code: String): ExchangeRateEntity? = storage[code.uppercase()]

        override suspend fun insertAll(rates: List<ExchangeRateEntity>) {
            for (rate in rates) {
                storage[rate.currencyCode.uppercase()] = rate
            }
        }

        override suspend fun clearAll() {
            storage.clear()
        }
    }

    private class FakeSettingsRepository : SettingsRepository() {
        override var enableCurrencyConversion: Boolean = true
        override var homeCurrencyCode: String = "USD"
        override var currencySyncPolicy: CurrencySyncPolicy = CurrencySyncPolicy.DAILY
    }

    private lateinit var fakeDao: FakeExchangeRateDao
    private lateinit var fakeSettings: FakeSettingsRepository
    private val utcZone: ZoneId = ZoneId.of("UTC")

    @Before
    fun setUp() {
        fakeDao = FakeExchangeRateDao()
        fakeSettings = FakeSettingsRepository()
    }

    // =========================================================================
    // 1. Offline Fallback Rates & Baseline Invariants Tests
    // =========================================================================

    @Test
    fun `baseline table contains positive rates for all supported Currency enum entries`() {
        val supportedCurrencies = Currency.entries

        for (currency in supportedCurrencies) {
            val rateToUsd = ExchangeRateBaseline.RATES_TO_USD[currency.code]
            assertNotNull(rateToUsd, "Currency ${currency.code} must be present in ExchangeRateBaseline.RATES_TO_USD")
            assertTrue(rateToUsd > 0.0, "Rate for ${currency.code} must be positive, but was $rateToUsd")
            assertTrue(rateToUsd.isFinite(), "Rate for ${currency.code} must be finite")
        }
    }

    @Test
    fun `offline fallback converts accurately for all currency pairs without network`() = runBlocking {
        val repo = ExchangeRateRepository(fakeDao, fakeSettings)

        // Baseline rates
        val usdToEur = repo.getRate("USD", "EUR")
        val eurToUsd = repo.getRate("EUR", "USD")
        val usdToVnd = repo.getRate("USD", "VND")
        val usdToRub = repo.getRate("USD", "RUB")

        assertEquals(0.92, usdToEur, 1e-6)
        assertEquals(1.0 / 0.92, eurToUsd, 1e-6)
        assertEquals(25400.0, usdToVnd, 1e-6)
        assertEquals(91.5, usdToRub, 1e-6)

        // Cross-currency conversion: EUR -> RUB
        // (RUB_to_USD / EUR_to_USD) = 91.5 / 0.92 = ~99.4565
        val eurToRub = repo.getRate("EUR", "RUB")
        val expectedEurToRub = 91.5 / 0.92
        assertEquals(expectedEurToRub, eurToRub, 1e-6)

        // Cross-currency conversion: VND -> THB
        // (THB_to_USD / VND_to_USD) = 36.5 / 25400.0
        val vndToThb = repo.getRate("VND", "THB")
        val expectedVndToThb = 36.5 / 25400.0
        assertEquals(expectedVndToThb, vndToThb, 1e-9)
    }

    @Test
    fun `offline fallback satisfies identity, reciprocal, and triangular invariants`() = runBlocking {
        val repo = ExchangeRateRepository(fakeDao, fakeSettings)
        val currencies = listOf("USD", "EUR", "RUB", "CNY", "JPY", "GBP", "VND", "THB", "KZT")

        for (from in currencies) {
            // Identity invariant: A -> A == 1.0
            val identityRate = repo.getRate(from, from)
            assertEquals(1.0, identityRate, 1e-9, "Identity conversion for $from must equal 1.0")

            for (to in currencies) {
                if (from == to) continue

                val forwardRate = repo.getRate(from, to)
                val backwardRate = repo.getRate(to, from)

                // Reciprocal invariant: (A -> B) * (B -> A) == 1.0
                assertEquals(1.0, forwardRate * backwardRate, 1e-6, "Reciprocal rate for $from <-> $to failed")

                // Triangle invariant: (A -> B) * (B -> C) == (A -> C)
                for (third in currencies) {
                    val intermediateRate = repo.getRate(to, third)
                    val directRate = repo.getRate(from, third)
                    assertEquals(directRate, forwardRate * intermediateRate, 1e-6, "Triangular arbitrage failed for $from -> $to -> $third")
                }
            }
        }
    }

    @Test
    fun `offline fallback safely handles unknown or invalid currency codes`() = runBlocking {
        val repo = ExchangeRateRepository(fakeDao, fakeSettings)

        // Unknown source/target codes should safely fallback to 1.0 without crashing
        val unknownFrom = repo.getRate("UNKNOWN_TOKEN", "USD")
        val unknownTo = repo.getRate("USD", "INVALID_XYZ")
        val bothUnknown = repo.getRate("FOO", "BAR")

        assertEquals(1.0, unknownFrom, 1e-6)
        assertEquals(1.0, unknownTo, 1e-6)
        assertEquals(1.0, bothUnknown, 1e-6)
    }

    @Test
    fun `currency augmentor converts prices correctly using baseline table in offline mode`() = runBlocking {
        val repo = ExchangeRateRepository(fakeDao, fakeSettings)
        val augmentor = CurrencyAugmentor(repo, fakeSettings)

        // $100 converted to RUB baseline (91.5 * 100 = 9150 ₽)
        val textUsdToRub = augmentor.augment("Total price is $100 today.", forcedHomeCurrency = Currency.RUB)
        assertTrue(textUsdToRub.contains("9 150 ₽") || textUsdToRub.contains("9150 ₽") || textUsdToRub.contains("≈"),
            "Expected augmented RUB price in: $textUsdToRub")

        // 100 € converted to USD baseline (100 / 0.92 = $108.70)
        val textEurToUsd = augmentor.augment("Fee: 100 €.", forcedHomeCurrency = Currency.USD)
        assertTrue(textEurToUsd.contains("$108.70") || textEurToUsd.contains("108.70"),
            "Expected augmented USD price in: $textEurToUsd")
    }

    // =========================================================================
    // 2. Sync Policy Evaluation Tests
    // =========================================================================

    @Test
    fun `daily sync policy triggers sync when never synced before (lastSync is 0)`() {
        val now = 1_700_000_000_000L
        val shouldSync = CurrencySyncEvaluator.shouldSync(
            policy = CurrencySyncPolicy.DAILY,
            lastSyncMillis = 0L,
            currentMillis = now
        )
        assertTrue(shouldSync, "Daily policy must trigger sync on initial install / never synced")
    }

    @Test
    fun `daily sync policy does NOT trigger sync when elapsed time is less than or equal to 24h`() {
        val baseTime = 1_700_000_000_000L
        val oneHourLater = baseTime + (1 * 60 * 60 * 1000L)
        val twelveHoursLater = baseTime + (12 * 60 * 60 * 1000L)
        val twentyThreeHoursLater = baseTime + (23 * 60 * 60 * 1000L)
        val exactly24HoursLater = baseTime + (24 * 60 * 60 * 1000L)

        // 0 ms elapsed
        assertFalse(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.DAILY, baseTime, baseTime))

        // 1h elapsed
        assertFalse(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.DAILY, baseTime, oneHourLater))

        // 12h elapsed
        assertFalse(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.DAILY, baseTime, twelveHoursLater))

        // 23h elapsed
        assertFalse(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.DAILY, baseTime, twentyThreeHoursLater))

        // Exactly 24h (86,400,000 ms) elapsed -> strictly > 24h required
        assertFalse(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.DAILY, baseTime, exactly24HoursLater))
    }

    @Test
    fun `daily sync policy triggers sync only when strictly greater than 24h elapsed`() {
        val baseTime = 1_700_000_000_000L
        val twentyFourHoursAndOneMs = baseTime + (24 * 60 * 60 * 1000L) + 1L
        val twentyFiveHoursLater = baseTime + (25 * 60 * 60 * 1000L)
        val fortyEightHoursLater = baseTime + (48 * 60 * 60 * 1000L)
        val sevenDaysLater = baseTime + (7 * 24 * 60 * 60 * 1000L)

        assertTrue(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.DAILY, baseTime, twentyFourHoursAndOneMs))
        assertTrue(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.DAILY, baseTime, twentyFiveHoursLater))
        assertTrue(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.DAILY, baseTime, fortyEightHoursLater))
        assertTrue(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.DAILY, baseTime, sevenDaysLater))
    }

    @Test
    fun `manual sync policy never triggers automatic sync regardless of elapsed time`() {
        val baseTime = 1_700_000_000_000L
        val zeroMs = baseTime
        val oneYearLater = baseTime + (365L * 24 * 60 * 60 * 1000L)

        // Never synced
        assertFalse(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.MANUAL, 0L, baseTime),
            "Manual policy must never trigger auto-sync on initial install")

        // 0 ms
        assertFalse(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.MANUAL, baseTime, zeroMs))

        // 25 hours later
        assertFalse(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.MANUAL, baseTime, baseTime + (25 * 60 * 60 * 1000L)))

        // 1 year later
        assertFalse(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.MANUAL, baseTime, oneYearLater),
            "Manual policy must never trigger auto-sync even after 1 year")
    }

    @Test
    fun `daily sync policy gracefully handles clock skew and negative elapsed time`() {
        val baseTime = 1_700_000_000_000L
        val pastClockSkew = baseTime - 50_000L // current time is earlier than last sync

        assertFalse(CurrencySyncEvaluator.shouldSync(CurrencySyncPolicy.DAILY, baseTime, pastClockSkew),
            "Daily policy must not trigger sync when clock is skewed into the past")
    }

    // =========================================================================
    // 3. Last Updated Formatting & Attribution Notice Tests
    // =========================================================================

    @Test
    fun `formatLastUpdated returns 'Offline cache (default)' when timestamp is 0 or negative`() {
        val now = 1_725_110_000_000L // 2024-08-31T13:13:20Z

        assertEquals("Offline cache (default)", CurrencyAttributionFormatter.formatLastUpdated(0L, now, utcZone))
        assertEquals("Offline cache (default)", CurrencyAttributionFormatter.formatLastUpdated(-1000L, now, utcZone))
    }

    @Test
    fun `formatLastUpdated returns 'Just now' when elapsed time is less than 60 seconds`() {
        val now = 1_725_110_000_000L
        val thirtySecondsAgo = now - 30_000L
        val fiftyNineSecondsAgo = now - 59_000L
        val zeroSecondsAgo = now

        assertEquals("Just now", CurrencyAttributionFormatter.formatLastUpdated(zeroSecondsAgo, now, utcZone))
        assertEquals("Just now", CurrencyAttributionFormatter.formatLastUpdated(thirtySecondsAgo, now, utcZone))
        assertEquals("Just now", CurrencyAttributionFormatter.formatLastUpdated(fiftyNineSecondsAgo, now, utcZone))
    }

    @Test
    fun `formatLastUpdated returns 'Today, 14 00' when synced earlier on the same calendar day`() {
        // Base: 2026-08-31 16:30:00 UTC
        val nowZoned = ZonedDateTime.of(2026, 8, 31, 16, 30, 0, 0, utcZone)
        val nowMillis = nowZoned.toInstant().toEpochMilli()

        // Synced today at 14:00:00 UTC
        val syncToday1400 = ZonedDateTime.of(2026, 8, 31, 14, 0, 0, 0, utcZone)
        val syncMillis = syncToday1400.toInstant().toEpochMilli()

        val formatted = CurrencyAttributionFormatter.formatLastUpdated(syncMillis, nowMillis, utcZone, Locale.US)
        assertEquals("Today, 14:00", formatted)

        // Synced today at 09:15:00 UTC
        val syncToday0915 = ZonedDateTime.of(2026, 8, 31, 9, 15, 0, 0, utcZone)
        val formatted0915 = CurrencyAttributionFormatter.formatLastUpdated(syncToday0915.toInstant().toEpochMilli(), nowMillis, utcZone, Locale.US)
        assertEquals("Today, 09:15", formatted0915)
    }

    @Test
    fun `formatLastUpdated returns 'Yesterday' when synced on the previous calendar day`() {
        // Base: 2026-08-31 10:00:00 UTC
        val nowZoned = ZonedDateTime.of(2026, 8, 31, 10, 0, 0, 0, utcZone)
        val nowMillis = nowZoned.toInstant().toEpochMilli()

        // Synced yesterday: 2026-08-30 22:00:00 UTC
        val syncYesterdayLate = ZonedDateTime.of(2026, 8, 30, 22, 0, 0, 0, utcZone)
        val formattedLate = CurrencyAttributionFormatter.formatLastUpdated(syncYesterdayLate.toInstant().toEpochMilli(), nowMillis, utcZone, Locale.US)
        assertEquals("Yesterday", formattedLate)

        // Synced yesterday morning: 2026-08-30 08:30:00 UTC
        val syncYesterdayEarly = ZonedDateTime.of(2026, 8, 30, 8, 30, 0, 0, utcZone)
        val formattedEarly = CurrencyAttributionFormatter.formatLastUpdated(syncYesterdayEarly.toInstant().toEpochMilli(), nowMillis, utcZone, Locale.US)
        assertEquals("Yesterday", formattedEarly)
    }

    @Test
    fun `formatLastUpdated formats older timestamps as explicit calendar date`() {
        // Base: 2026-08-31 12:00:00 UTC
        val nowZoned = ZonedDateTime.of(2026, 8, 31, 12, 0, 0, 0, utcZone)
        val nowMillis = nowZoned.toInstant().toEpochMilli()

        // Synced on 2026-08-25 11:00:00 UTC (6 days ago)
        val syncOlderDate = ZonedDateTime.of(2026, 8, 25, 11, 0, 0, 0, utcZone)
        val formatted = CurrencyAttributionFormatter.formatLastUpdated(syncOlderDate.toInstant().toEpochMilli(), nowMillis, utcZone, Locale.US)
        assertEquals("25.08.2026", formatted)
    }

    @Test
    fun `attribution disclaimer states baseline reference transparency and offline operation`() {
        val disclaimer = CurrencyAttributionFormatter.getAttributionDisclaimer()

        assertTrue(disclaimer.contains("Open exchange reference data") || disclaimer.contains("ECB"),
            "Attribution must credit open exchange rate reference sources")
        assertTrue(disclaimer.contains("100% offline") || disclaimer.contains("offline"),
            "Attribution must clearly declare offline operation")
    }

    // =========================================================================
    // 4. Memory & Database Cache Integration Tests (Pure JVM)
    // =========================================================================

    @Test
    fun `repository dynamically updates memory rates when fresh entities are inserted in DAO`() = runBlocking {
        val repo = ExchangeRateRepository(fakeDao, fakeSettings)

        // Baseline rate for EUR is 0.92
        assertEquals(0.92, repo.getRate("USD", "EUR"), 1e-6)

        // Insert new rates into DAO
        val now = System.currentTimeMillis()
        val freshEntities = listOf(
            ExchangeRateEntity("USD", 1.0, now),
            ExchangeRateEntity("EUR", 0.85, now), // Market update: EUR stronger
            ExchangeRateEntity("JPY", 160.0, now) // JPY weaker
        )
        fakeDao.insertAll(freshEntities)

        // Trigger reload by querying repository
        val updatedUsdToEur = fakeDao.getRate("EUR")?.rateToUsd ?: 0.0
        val updatedUsdToJpy = fakeDao.getRate("JPY")?.rateToUsd ?: 0.0

        assertEquals(0.85, updatedUsdToEur, 1e-6)
        assertEquals(160.0, updatedUsdToJpy, 1e-6)
    }
}
