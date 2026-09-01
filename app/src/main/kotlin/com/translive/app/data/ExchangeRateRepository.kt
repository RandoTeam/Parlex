package com.translive.app.data

import android.util.Log
import com.translive.app.data.db.ExchangeRateDao
import com.translive.app.data.model.ExchangeRateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExchangeRateRepository @Inject constructor(
    private val exchangeRateDao: ExchangeRateDao,
    private val settingsRepository: SettingsRepository
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val memoryRates = mutableMapOf<String, Double>()
    private var lastLoadedTime = 0L

    private val _lastUpdatedMillis = kotlinx.coroutines.flow.MutableStateFlow<Long?>(null)
    val lastUpdatedMillis: kotlinx.coroutines.flow.StateFlow<Long?> = _lastUpdatedMillis.asStateFlow()

    companion object {
        private const val TAG = "ExchangeRateRepo"
        const val TTL_MILLIS = 24 * 60 * 60 * 1000L // 24 Hours
    }

    init {
        // Seed immediately from offline baseline
        memoryRates.putAll(ExchangeRateBaseline.RATES_TO_USD)
    }

    suspend fun getLastUpdatedTimestamp(): Long? = withContext(Dispatchers.IO) {
        val dbList = exchangeRateDao.getAllRatesList()
        val ts = dbList.firstOrNull()?.lastUpdatedMillis
        _lastUpdatedMillis.value = ts
        ts
    }

    suspend fun getRate(fromCurrency: String, toCurrency: String): Double = withContext(Dispatchers.IO) {
        val from = fromCurrency.uppercase()
        val to = toCurrency.uppercase()
        if (from == to) return@withContext 1.0

        ensureRatesLoaded()

        val fromToUsd = memoryRates[from] ?: ExchangeRateBaseline.RATES_TO_USD[from] ?: 1.0
        val toToUsd = memoryRates[to] ?: ExchangeRateBaseline.RATES_TO_USD[to] ?: 1.0

        if (fromToUsd <= 0.0) return@withContext 1.0
        return@withContext toToUsd / fromToUsd
    }

    private suspend fun ensureRatesLoaded() {
        if (System.currentTimeMillis() - lastLoadedTime < 300_000L && memoryRates.size > 10) {
            return
        }

        try {
            val dbList = exchangeRateDao.getAllRatesList()
            val lastSync = dbList.firstOrNull()?.lastUpdatedMillis ?: 0L
            _lastUpdatedMillis.value = if (lastSync > 0) lastSync else null

            if (dbList.isNotEmpty()) {
                synchronized(memoryRates) {
                    for (entity in dbList) {
                        memoryRates[entity.currencyCode] = entity.rateToUsd
                    }
                    lastLoadedTime = lastSync
                }
            }

            val now = System.currentTimeMillis()
            val shouldAutoSync = CurrencySyncEvaluator.shouldSync(
                policy = settingsRepository.currencySyncPolicy,
                lastSyncMillis = lastSync,
                currentMillis = now
            )

            if (shouldAutoSync && settingsRepository.enableCurrencyConversion) {
                refreshRates()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error checking exchange rate cache: ${e.message}")
        }
    }

    suspend fun refreshRates(): Boolean = withContext(Dispatchers.IO) {
        try {
            val rates = fetchFromFrankfurter()
                ?: fetchFromFloatRates()
                ?: fetchFromOpenErApi()

            if (rates != null && rates.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val entities = rates.map { (code, rate) ->
                    ExchangeRateEntity(code, rate, now)
                }
                exchangeRateDao.insertAll(entities)
                synchronized(memoryRates) {
                    memoryRates.clear()
                    memoryRates.putAll(ExchangeRateBaseline.RATES_TO_USD)
                    memoryRates.putAll(rates)
                    lastLoadedTime = now
                }
                _lastUpdatedMillis.value = now
                Log.d(TAG, "Successfully refreshed ${rates.size} live exchange rates")
                return@withContext true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh online exchange rates, using cached baseline: ${e.message}")
        }
        return@withContext false
    }

    private fun fetchFromFloatRates(): Map<String, Double>? {
        return try {
            val request = Request.Builder()
                .url("https://www.floatrates.com/daily/usd.json")
                .header("User-Agent", "Parlex-App")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val map = mutableMapOf("USD" to 1.0)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = json.getJSONObject(key)
                    val code = obj.getString("code").uppercase()
                    val rate = obj.getDouble("rate")
                    map[code] = rate
                }
                map
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromOpenErApi(): Map<String, Double>? {
        return try {
            val request = Request.Builder()
                .url("https://open.er-api.com/v6/latest/USD")
                .header("User-Agent", "Parlex-App")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val ratesObj = json.getJSONObject("rates")
                val map = mutableMapOf<String, Double>()
                val keys = ratesObj.keys()
                while (keys.hasNext()) {
                    val code = keys.next().uppercase()
                    map[code] = ratesObj.getDouble(code)
                }
                map
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchFromFrankfurter(): Map<String, Double>? {
        return try {
            val request = Request.Builder()
                .url("https://api.frankfurter.dev/v1/latest?base=USD")
                .header("User-Agent", "Parlex-App")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val ratesObj = json.getJSONObject("rates")
                val map = mutableMapOf("USD" to 1.0)
                val keys = ratesObj.keys()
                while (keys.hasNext()) {
                    val code = keys.next().uppercase()
                    map[code] = ratesObj.getDouble(code)
                }
                map
            }
        } catch (_: Exception) {
            null
        }
    }
}
