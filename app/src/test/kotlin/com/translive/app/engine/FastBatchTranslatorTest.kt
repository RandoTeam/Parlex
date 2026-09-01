package com.translive.app.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM unit test for FastBatchTranslator (Phase W1: Zero-Latency Hybrid Screen Pipeline).
 * Tests:
 * 1. Empty and single-line handling.
 * 2. 1-Pass Delimited Sentinel Batch Protocol (\n\n<<<§>>>\n\n).
 * 3. Delimiter splitting and whitespace normalization.
 * 4. Resilient fallback when NMT collapses delimiters.
 */
class FastBatchTranslatorTest {

    @Test
    fun translateBatch_emptyList_returnsEmptyList() = runBlocking {
        var calls = 0
        val translator = FastBatchTranslator(
            translateFunc = { text ->
                calls++
                text
            }
        )

        val result = translator.translateBatch(emptyList())
        assertEquals(0, result.size)
        assertEquals(0, calls)
    }

    @Test
    fun translateBatch_singleItem_callsDirectlyWithoutDelimiters() = runBlocking {
        var capturedPayload = ""
        val translator = FastBatchTranslator(
            translateFunc = { text ->
                capturedPayload = text
                "Привет мир"
            }
        )

        val result = translator.translateBatch(listOf("Hello world"))
        assertEquals(listOf("Привет мир"), result)
        assertEquals("Hello world", capturedPayload)
    }

    @Test
    fun translateBatch_multipleItems_joinsWithDelimitersAndSplitsInOnePass() = runBlocking {
        var callCount = 0
        var capturedPayload = ""
        val translator = FastBatchTranslator(
            translateFunc = { text ->
                callCount++
                capturedPayload = text
                // Simulate NMT translating while keeping delimiters intact
                "Главная\n\n<<<§>>>\n\nНастройки\n\n<<<§>>>\n\nПрофиль пользователя"
            }
        )

        val inputs = listOf("Home", "Settings", "User Profile")
        val result = translator.translateBatch(inputs)

        assertEquals(1, callCount)
        assertEquals(3, result.size)
        assertEquals("Главная", result[0])
        assertEquals("Настройки", result[1])
        assertEquals("Профиль пользователя", result[2])
    }

    @Test
    fun translateBatch_delimiterCollapse_fallsBackToIndividualTranslation() = runBlocking {
        var callCount = 0
        val translator = FastBatchTranslator(
            translateFunc = { text ->
                callCount++
                if (callCount == 1) {
                    // Simulate NMT corrupting/collapsing delimiter into single paragraph
                    "Главная Настройки Профиль"
                } else {
                    // Fallback individual calls
                    when (text) {
                        "Home" -> "Главная"
                        "Settings" -> "Настройки"
                        "User Profile" -> "Профиль"
                        else -> text
                    }
                }
            }
        )

        val inputs = listOf("Home", "Settings", "User Profile")
        val result = translator.translateBatch(inputs)

        // 1 initial batch call + 3 fallback calls = 4
        assertEquals(4, callCount)
        assertEquals(3, result.size)
        assertEquals("Главная", result[0])
        assertEquals("Настройки", result[1])
        assertEquals("Профиль", result[2])
    }
}
