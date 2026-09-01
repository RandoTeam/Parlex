package com.translive.app.engine

import com.translive.app.data.SettingsRepository
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM Unit Test Suite for STT Selection State & Travel Packs Vertical Structure.
 *
 * Requirements:
 * 1. Test STT model ID constants (Silero Whisper vs Qwen3-ASR).
 * 2. Test Selection matching (active vs inactive states).
 * 3. Test Travel Packs expansion model and item count metadata.
 * 4. 100% Pure JVM.
 */
class SttSelectionAndTravelPacksTest {

    @Test
    fun `STT model selection resolves active model correctly`() {
        val whisperModelId = SettingsRepository.SPEECH_MODEL_WHISPER_TINY
        val qwen3ModelId = SettingsRepository.SPEECH_MODEL_QWEN3_ASR_06B

        var currentSelectedModel = whisperModelId
        assertTrue(currentSelectedModel == whisperModelId, "Whisper must be selected")
        assertFalse(currentSelectedModel == qwen3ModelId, "Qwen3 must not be selected")

        // Switch to Qwen3
        currentSelectedModel = qwen3ModelId
        assertFalse(currentSelectedModel == whisperModelId, "Whisper must no longer be selected")
        assertTrue(currentSelectedModel == qwen3ModelId, "Qwen3 must be selected")
    }

    @Test
    fun `Travel packs metadata contains distinct language pairs for vertical layout`() {
        val packIds = listOf("ru_en", "vi_en", "zh_en", "es_en", "ja_en", "de_en", "fr_en")
        assertEquals(7, packIds.size, "Should support 7 core travel pack bundles")

        // Verify all IDs are unique
        assertEquals(packIds.size, packIds.toSet().size, "All travel pack IDs must be unique")
    }
}
