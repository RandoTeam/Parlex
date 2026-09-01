package com.translive.app.data

import com.translive.app.data.model.CurrencySyncPolicy
import java.time.Duration

/**
 * Pure evaluator for currency rate synchronization eligibility.
 */
object CurrencySyncEvaluator {

    val SYNC_INTERVAL_DAILY: Duration = Duration.ofHours(24)

    /**
     * Determines whether an automatic sync should trigger based on policy and timestamp.
     *
     * @param policy Active sync policy (DAILY, ON_LAUNCH, or MANUAL).
     * @param lastSyncMillis Timestamp in ms of last successful sync (0L if never synced).
     * @param currentMillis Current timestamp in ms.
     * @return true if automatic background sync must be executed, false otherwise.
     */
    fun shouldSync(
        policy: CurrencySyncPolicy,
        lastSyncMillis: Long,
        currentMillis: Long
    ): Boolean {
        return when (policy) {
            CurrencySyncPolicy.MANUAL -> false
            CurrencySyncPolicy.DAILY -> {
                if (lastSyncMillis <= 0L) return true
                val elapsedMillis = currentMillis - lastSyncMillis
                elapsedMillis > SYNC_INTERVAL_DAILY.toMillis()
            }
            CurrencySyncPolicy.ON_LAUNCH -> true
        }
    }
}
