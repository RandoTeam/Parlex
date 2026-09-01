package com.translive.app.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formatter for exchange rate metadata, relative timestamps, and open reference attribution.
 */
object CurrencyAttributionFormatter {

    const val DEFAULT_OFFLINE_CACHE_LABEL = "Offline cache (default)"
    const val JUST_NOW_LABEL = "Just now"
    const val YESTERDAY_LABEL = "Yesterday"
    const val ATTRIBUTION_SOURCE = "Open exchange reference data (ECB / Central Bank baseline)"

    /**
     * Formats relative timestamp based on last sync epoch time.
     *
     * @param lastUpdatedMillis Epoch ms of the last rate sync (0L if default baseline).
     * @param nowMillis Epoch ms of current time.
     * @param zoneId Timezone for day boundary evaluation (defaults to system/UTC).
     * @param locale Locale for formatting.
     */
    fun formatLastUpdated(
        lastUpdatedMillis: Long,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.of("UTC"),
        locale: Locale = Locale.US
    ): String {
        if (lastUpdatedMillis <= 0L) {
            return DEFAULT_OFFLINE_CACHE_LABEL
        }

        val elapsedMs = nowMillis - lastUpdatedMillis
        if (elapsedMs < 0L) {
            // Future timestamp / clock skew protection
            return DEFAULT_OFFLINE_CACHE_LABEL
        }

        // Less than 60 seconds -> 'Just now'
        if (elapsedMs < 60_000L) {
            return JUST_NOW_LABEL
        }

        val syncInstant = Instant.ofEpochMilli(lastUpdatedMillis)
        val nowInstant = Instant.ofEpochMilli(nowMillis)

        val syncZoned = syncInstant.atZone(zoneId)
        val nowZoned = nowInstant.atZone(zoneId)

        val syncDate = syncZoned.toLocalDate()
        val nowDate = nowZoned.toLocalDate()

        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
        val timeString = syncZoned.format(timeFormatter)

        return when {
            syncDate == nowDate -> "Today, $timeString"
            syncDate == nowDate.minusDays(1) -> YESTERDAY_LABEL
            else -> {
                val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", locale)
                syncZoned.format(dateFormatter)
            }
        }
    }

    /**
     * Full transparency attribution disclaimer for Settings UI card.
     */
    fun getAttributionDisclaimer(): String {
        return "$ATTRIBUTION_SOURCE. Rates operate 100% offline."
    }
}
