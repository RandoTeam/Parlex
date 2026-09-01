package com.translive.app.data.model

/**
 * Currency synchronization policies configurable by user in Settings.
 */
enum class CurrencySyncPolicy(val id: String) {
    DAILY("daily"),
    ON_LAUNCH("on_launch"),
    MANUAL("manual");

    companion object {
        fun fromId(id: String?): CurrencySyncPolicy =
            entries.find { it.id.equals(id, ignoreCase = true) } ?: DAILY
    }
}
