package com.translive.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit test suite verifying ScreenTextNoiseFilter:
 * 1. Filtering solitary timestamps (e.g. 14:30, 09:15 AM, 23:59:00, 5m ago).
 * 2. Filtering solitary battery levels and percentages (e.g. 98%, 100%, 45 %).
 * 3. Filtering pure numbers, counts, currencies, and phone numbers without letters (e.g. 1,000, $19.99, +123456789).
 * 4. Filtering solitary URLs, domains, and filesystem paths.
 * 5. Filtering lone punctuation, emojis, and symbols (e.g. •, ..., —, |).
 * 6. Filtering single isolated characters (e.g. "X", "1", "q").
 * 7. Preserving meaningful natural phrases, words, and sentences in various languages.
 */
class ScreenTextNoiseFilterTest {

    @Test
    fun isNoise_timestamps_returnsTrue() {
        val timestamps = listOf(
            "12:45",
            "14:30:15",
            "09:15 AM",
            "11:00 PM",
            "23:59",
            "8:30pm",
            "5m ago",
            "2h ago",
            "3d ago",
            "12s"
        )
        for (ts in timestamps) {
            assertTrue("Expected timestamp '$ts' to be recognized as noise", ScreenTextNoiseFilter.isNoise(ts))
        }
    }

    @Test
    fun isNoise_percentagesAndBattery_returnsTrue() {
        val percentages = listOf(
            "100%",
            "98%",
            "45 %",
            "0%",
            "+12.5%",
            "-3%"
        )
        for (p in percentages) {
            assertTrue("Expected percentage '$p' to be recognized as noise", ScreenTextNoiseFilter.isNoise(p))
        }
    }

    @Test
    fun isNoise_standaloneNumbersAndCurrencies_returnsTrue() {
        val numbers = listOf(
            "42",
            "1,234",
            "1000000",
            "3.14159",
            "+1234567890",
            "(555) 123-4567",
            "#123",
            "$19.99",
            "€45",
            "£100",
            "¥5000",
            "12/34",
            "2026-09-13"
        )
        for (num in numbers) {
            assertTrue("Expected numeric/currency '$num' to be recognized as noise", ScreenTextNoiseFilter.isNoise(num))
        }
    }

    @Test
    fun isNoise_urlsAndPaths_returnsTrue() {
        val urls = listOf(
            "https://google.com",
            "http://example.org/path/to/page",
            "www.github.com",
            "github.com/RandoTeam/Parlex",
            "/storage/emulated/0/Download",
            "C:\\Windows\\System32",
            "https://www.androidpolice.com › author › ...",
            "https://www.youtube.com › @AndroidPolice",
            "androidpolice.com › phones › best-android-phones"
        )
        for (url in urls) {
            assertTrue("Expected URL/path '$url' to be recognized as noise", ScreenTextNoiseFilter.isNoise(url))
        }
    }

    @Test
    fun isNoise_solitaryPunctuationAndSymbols_returnsTrue() {
        val symbols = listOf(
            "•",
            "...",
            "—",
            "|",
            ">>>",
            "***",
            "---",
            "&",
            "✓",
            "★",
            "   "
        )
        for (sym in symbols) {
            assertTrue("Expected symbol '$sym' to be recognized as noise", ScreenTextNoiseFilter.isNoise(sym))
        }
    }

    @Test
    fun isNoise_singleIsolatedCharacters_returnsTrue() {
        val chars = listOf("A", "X", "q", "1", "9")
        for (c in chars) {
            assertTrue("Expected isolated character '$c' to be recognized as noise", ScreenTextNoiseFilter.isNoise(c))
        }
    }

    @Test
    fun isNoise_meaningfulText_returnsFalse() {
        val naturalTexts = listOf(
            "Settings",
            "Turn on Dark Mode",
            "Welcome to Parlex Translation",
            "How are you today?",
            "Привет, как дела?",
            "Настройки экрана",
            "你好世界",
            "Guten Morgen",
            "Bonjour le monde",
            "Total: $19.99", // Contains natural text + number
            "Updated 5m ago by Alice", // Sentence with time
            "Battery low (15%), please recharge" // Sentence with percentage
        )
        for (text in naturalTexts) {
            assertFalse("Expected meaningful text '$text' NOT to be noise", ScreenTextNoiseFilter.isNoise(text))
        }
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = android.graphics.Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    @Test
    fun isTranslatable_rejectsMassiveScreenContainers() {
        val screenBounds = rect(0, 0, 1440, 3168)
        // Giant container covering 88% width and 70% height
        val containerBounds = rect(50, 200, 1390, 2600)
        val containerText = "Android Police news articles and search settings..."

        val result = ScreenTextNoiseFilter.isTranslatable(containerText, containerBounds, screenBounds)
        assertFalse("Massive whole-screen containers must be rejected", result)

        // Normal card (90% width, but only 10% height)
        val cardBounds = rect(50, 400, 1390, 700)
        val cardText = "Android Police: Android news, reviews, guides"
        assertTrue("Normal card must be translatable", ScreenTextNoiseFilter.isTranslatable(cardText, cardBounds, screenBounds))
    }
}
