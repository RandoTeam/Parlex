package com.translive.app.engine

import com.translive.app.engine.stt.SttEngineDescriptor
import com.translive.app.engine.stt.SttEngineRegistry
import com.translive.app.engine.stt.SttEngineSelector
import com.translive.app.engine.stt.SttEngineType
import com.translive.app.engine.stt.TwoPassSttPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit test suite verifying Phase V: Multi-Engine STT Architecture:
 * 1. Engine registry catalogs Whisper, SenseVoice, Zipformer, and Qwen3-ASR with exact 2026 metadata.
 * 2. Language coverage validation: SenseVoice (ZH, EN, JA, KO, YUE), Zipformer (RU, EN, ZH, VI), Whisper (all).
 * 3. Dynamic engine selector picks the optimal engine based on active language and download status.
 * 4. Two-pass ASR pipeline: Fast streaming partial updates followed by offline rescoring completion.
 */
class MultiEngineSttTest {

    // =========================================================================
    // SECTION 1: Engine Registry Metadata & 2026 Audit
    // =========================================================================

    @Test
    fun registry_containsAllFourSttEngineTypes() {
        val registry = SttEngineRegistry()
        val engines = registry.getAllEngines()

        assertEquals(4, engines.size)
        assertTrue(engines.any { it.type == SttEngineType.WHISPER_TINY })
        assertTrue(engines.any { it.type == SttEngineType.SENSE_VOICE_SMALL })
        assertTrue(engines.any { it.type == SttEngineType.ZIPFORMER_STREAMING })
        assertTrue(engines.any { it.type == SttEngineType.QWEN3_ASR })
    }

    @Test
    fun senseVoice_metadata_reflects2026Audit() {
        val registry = SttEngineRegistry()
        val senseVoice = registry.getEngine(SttEngineType.SENSE_VOICE_SMALL)

        assertNotNull(senseVoice)
        assertEquals("SenseVoice Small INT8", senseVoice!!.displayName)
        assertFalse("SenseVoice is offline batch/chunk model", senseVoice.isNativeStreaming)
        // Verify SenseVoice does NOT claim Russian support (key 2026 finding)
        assertFalse("SenseVoice should not support RU", senseVoice.supportedLanguages.contains("ru"))
        assertTrue("SenseVoice supports ZH", senseVoice.supportedLanguages.contains("zh"))
        assertTrue("SenseVoice supports EN", senseVoice.supportedLanguages.contains("en"))
        assertTrue("SenseVoice supports JA", senseVoice.supportedLanguages.contains("ja"))
        assertTrue("SenseVoice supports KO", senseVoice.supportedLanguages.contains("ko"))
    }

    @Test
    fun zipformer_metadata_reflectsStreamingSupport() {
        val registry = SttEngineRegistry()
        val zipformer = registry.getEngine(SttEngineType.ZIPFORMER_STREAMING)

        assertNotNull(zipformer)
        assertTrue("Zipformer must be native streaming", zipformer!!.isNativeStreaming)
        assertTrue("Zipformer supports RU streaming package", zipformer.supportedLanguages.contains("ru"))
        assertTrue("Zipformer supports EN streaming package", zipformer.supportedLanguages.contains("en"))
        assertTrue("Zipformer supports VI streaming package", zipformer.supportedLanguages.contains("vi"))
    }

    // =========================================================================
    // SECTION 2: Dynamic Engine Selection
    // =========================================================================

    @Test
    fun engineSelector_whenZipformerDownloaded_selectsZipformerForStreaming() {
        val selector = SttEngineSelector(
            downloadedEngines = setOf(SttEngineType.WHISPER_TINY, SttEngineType.ZIPFORMER_STREAMING),
            preferStreaming = true
        )

        val selectedForRu = selector.selectBestEngine(languageCode = "ru")
        assertEquals(SttEngineType.ZIPFORMER_STREAMING, selectedForRu)

        val selectedForEn = selector.selectBestEngine(languageCode = "en")
        assertEquals(SttEngineType.ZIPFORMER_STREAMING, selectedForEn)
    }

    @Test
    fun engineSelector_forRareLanguage_fallsBackToWhisper() {
        val selector = SttEngineSelector(
            downloadedEngines = setOf(SttEngineType.WHISPER_TINY, SttEngineType.SENSE_VOICE_SMALL),
            preferStreaming = false
        )

        // German (de) is not in SenseVoice 5 core langs
        val selectedForDe = selector.selectBestEngine(languageCode = "de")
        assertEquals(SttEngineType.WHISPER_TINY, selectedForDe)
    }

    // =========================================================================
    // SECTION 3: Two-Pass STT Pipeline State Machine
    // =========================================================================

    @Test
    fun twoPassPipeline_aggregatesStreamingPartialsAndReplacesWithFinalRescored() {
        val pipeline = TwoPassSttPipeline()

        // 1st pass: Streaming partials arrive
        pipeline.onStreamingPartial("привет")
        assertEquals("привет", pipeline.currentLiveText)
        assertFalse(pipeline.isFinalized)

        pipeline.onStreamingPartial("привет как дела")
        assertEquals("привет как дела", pipeline.currentLiveText)

        // 2nd pass: Rescoring engine produces high-precision final utterance
        pipeline.onRescoringFinal("Привет, как дела?")
        assertEquals("Привет, как дела?", pipeline.currentLiveText)
        assertTrue(pipeline.isFinalized)
    }
}
