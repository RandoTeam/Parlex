package com.translive.app.service

import android.graphics.Rect

/**
 * Intelligent Text Noise Filter for AR Screen Translation.
 * Eliminates solitary timestamps, battery percentages, standalone numbers, currencies,
 * URLs, filesystem paths, and lone symbols, ensuring only meaningful natural language is translated.
 */
object ScreenTextNoiseFilter {

    // 1. Solitary Timestamps (e.g. "14:30", "9:41 AM", "08:15:30", "5m ago", "2h ago", "12s")
    private val TIMESTAMP_REGEX = Regex(
        """^(?:(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?(?:\s?[AaPp][Mm])?|\d{1,2}\s*[smhdw](\s*ago)?)$""",
        RegexOption.IGNORE_CASE
    )

    // 2. Battery & Percentages (e.g. "95%", "100 %", "0%", "+12.5%", "-3%")
    private val PERCENTAGE_REGEX = Regex(
        """^[+-]?\d+(?:\.\d+)?\s*%$"""
    )

    // 3. Standalone Numbers, Phone Numbers, Math, Dates, & Serials without natural words
    private val PURE_NUMBER_OR_PUNCTUATION_REGEX = Regex(
        """^[#+]?[0-9\s,\.\-/:()]+$"""
    )

    // 4. Standalone Currencies & Prices without natural words (e.g. "$19.99", "€45", "£100", "¥5000", "1200 ₽")
    private val CURRENCY_PRICE_REGEX = Regex(
        """^[$€£¥₽₹₩₫฿]?\s*\d+(?:[\.,]\d+)*\s*[$€£¥₽₹₩₫฿]?$"""
    )

    // 5. Standalone URLs, Domains, Emails, & Filesystem Paths
    private val URL_OR_PATH_REGEX = Regex(
        """^(?:(?:https?://|www\.)\S+|\S+\.(?:com|org|net|io|ai|app|dev|edu|gov|ru|vn|cn|de|uk|jp)(?:/\S*)?|[\w\.-]+@[\w\.-]+\.\w+|[A-Za-z]:\\[^\n\r]+|/(?:storage|sdcard|data|system)/[^\n\r]+)$""",
        RegexOption.IGNORE_CASE
    )

    // 6. Solitary Punctuation, Bullets, and Symbols only
    private val SYMBOLS_ONLY_REGEX = Regex(
        """^[\p{P}\p{S}\s]+$"""
    )

    /**
     * Returns true if [text] is considered noise (timestamps, percentages, pure numbers, URLs, symbols)
     * and should NOT be sent to the translation engine.
     */
    fun isNoise(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return true

        // Filter out solitary symbols, emojis, bullets
        if (SYMBOLS_ONLY_REGEX.matches(trimmed)) return true
        if (!trimmed.any { it.isLetterOrDigit() }) return true

        // Filter out single isolated characters without context
        if (trimmed.length == 1 && !isCjk(trimmed.first())) return true

        // Filter out timestamps
        if (TIMESTAMP_REGEX.matches(trimmed)) return true

        // Filter out battery and percentages
        if (PERCENTAGE_REGEX.matches(trimmed)) return true

        // Filter out currency prices
        if (CURRENCY_PRICE_REGEX.matches(trimmed)) return true

        // Filter out pure numbers, dates, phone numbers
        val hasLetter = trimmed.any { it.isLetter() }
        if (!hasLetter && PURE_NUMBER_OR_PUNCTUATION_REGEX.matches(trimmed)) return true

        // Filter out standalone URLs and filesystem paths
        if (URL_OR_PATH_REGEX.matches(trimmed)) return true

        return false
    }

    /**
     * Determines whether an OCR cluster or A11y text node should be submitted to neural translation.
     */
    fun isTranslatable(text: String, bounds: Rect? = null, screenBounds: Rect? = null): Boolean {
        if (isNoise(text)) return false

        // Optional geometric filter: discard status bar top chrome (top 28dp) if it looks like clock/battery
        if (bounds != null && screenBounds != null) {
            val topChromeLimit = screenBounds.top + (32f * (screenBounds.height() / 800f))
            if (bounds.top < topChromeLimit) {
                val trimmed = text.trim()
                if (TIMESTAMP_REGEX.containsMatchIn(trimmed) || PERCENTAGE_REGEX.containsMatchIn(trimmed)) {
                    return false
                }
            }
        }

        return true
    }

    private fun isCjk(c: Char): Boolean {
        val ub = Character.UnicodeBlock.of(c)
        return ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            ub == Character.UnicodeBlock.HIRAGANA ||
            ub == Character.UnicodeBlock.KATAKANA ||
            ub == Character.UnicodeBlock.HANGUL_SYLLABLES
    }
}
