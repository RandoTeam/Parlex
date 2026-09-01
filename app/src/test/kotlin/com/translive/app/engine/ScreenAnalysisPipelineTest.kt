package com.translive.app.engine

import com.translive.app.engine.vision.ImageDimensionScaler
import com.translive.app.engine.vision.ScaledDimensions
import com.translive.app.engine.vision.StreamingTextAccumulator
import com.translive.app.engine.vision.VisionAnalysisPromptType
import com.translive.app.engine.vision.VisionLlmCatalog
import com.translive.app.engine.vision.VisionLlmModelType
import com.translive.app.engine.vision.VisionPromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit test suite verifying Phase AI: On-Device Vision LLM & Screen Analysis Pipeline:
 * 1. Vision LLM Catalog metadata verification (MiniCPM-V 4.6, SmolVLM-2, Gemma 4 Edge E2B).
 * 2. Image pre-processor aspect-ratio preserving dimension downscaling math for mobile RAM safety.
 * 3. Structured system prompt builder for translation, explanation, and visual summarization.
 * 4. Streaming token accumulator and cancellation state machine.
 */
class ScreenAnalysisPipelineTest {

    // =========================================================================
    // SECTION 1: Vision LLM Catalog (2026 Audit)
    // =========================================================================

    @Test
    fun catalog_containsAllThreeTargetVlmModels() {
        val catalog = VisionLlmCatalog()
        val models = catalog.getAllModels()

        assertEquals(3, models.size)
        assertTrue(models.any { it.type == VisionLlmModelType.MINI_CPM_V_4_6 })
        assertTrue(models.any { it.type == VisionLlmModelType.SMOL_VLM_2 })
        assertTrue(models.any { it.type == VisionLlmModelType.GEMMA_4_EDGE_E2B })
    }

    @Test
    fun miniCpmV_metadata_matches2026Specifications() {
        val catalog = VisionLlmCatalog()
        val model = catalog.getModel(VisionLlmModelType.MINI_CPM_V_4_6)

        assertNotNull(model)
        assertEquals("MiniCPM-V 4.6 INT4", model!!.displayName)
        assertTrue("Size should be under 1.2 GB", model.sizeBytes < 1_300_000_000L)
        assertTrue("RAM estimate should be around 1.5 GB", model.ramEstimateMb <= 1800)
    }

    @Test
    fun smolVlm2_metadata_isUltraCompact() {
        val catalog = VisionLlmCatalog()
        val model = catalog.getModel(VisionLlmModelType.SMOL_VLM_2)

        assertNotNull(model)
        assertEquals("SmolVLM-2 500M INT4", model!!.displayName)
        assertTrue("Size should be under 400 MB", model.sizeBytes < 400_000_000L)
        assertTrue("RAM estimate should be under 800 MB", model.ramEstimateMb <= 800)
    }

    // =========================================================================
    // SECTION 2: Image Dimension Scaler Math
    // =========================================================================

    @Test
    fun imageScaler_fullHdPortrait_scalesDownPreservingAspectRatio() {
        // 1080 x 2160 screen (2:1 aspect ratio) with max target dimension 1024
        val scaled: ScaledDimensions = ImageDimensionScaler.computeScaledDimensions(
            sourceWidth = 1080,
            sourceHeight = 2160,
            maxDimension = 1024
        )

        assertEquals(512, scaled.width)
        assertEquals(1024, scaled.height)
        assertEquals(0.5f, scaled.aspectRatio, 0.01f)
    }

    @Test
    fun imageScaler_smallImage_isNotScaledUp() {
        val scaled = ImageDimensionScaler.computeScaledDimensions(
            sourceWidth = 320,
            sourceHeight = 240,
            maxDimension = 1024
        )

        assertEquals(320, scaled.width)
        assertEquals(240, scaled.height)
    }

    // =========================================================================
    // SECTION 3: Vision Prompt Construction
    // =========================================================================

    @Test
    fun promptBuilder_translateAndExplain_includesContextInstruction() {
        val prompt = VisionPromptBuilder.buildPrompt(
            type = VisionAnalysisPromptType.TRANSLATE_AND_EXPLAIN,
            targetLangName = "Русский"
        )

        assertTrue(prompt.contains("Переведи"))
        assertTrue(prompt.contains("Русский"))
    }

    @Test
    fun promptBuilder_summarize_requestsKeyTakeaways() {
        val prompt = VisionPromptBuilder.buildPrompt(
            type = VisionAnalysisPromptType.SUMMARIZE_SCREEN,
            targetLangName = "Русский"
        )

        assertTrue(prompt.contains("Суммаризируй") || prompt.contains("главные тезисы"))
    }

    // =========================================================================
    // SECTION 4: Streaming Token Accumulator
    // =========================================================================

    @Test
    fun tokenAccumulator_accumulatesTokensAndRespectsCancellation() {
        val accumulator = StreamingTextAccumulator()

        accumulator.appendToken("На ")
        accumulator.appendToken("этом ")
        accumulator.appendToken("экране...")

        assertEquals("На этом экране...", accumulator.currentText)
        assertFalse(accumulator.isCancelled)

        accumulator.cancel()
        assertTrue(accumulator.isCancelled)

        // Tokens after cancellation should be dropped
        accumulator.appendToken("лишний текст")
        assertEquals("На этом экране...", accumulator.currentText)
    }
}
