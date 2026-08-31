package com.translive.app.engine.camera

import android.graphics.Rect
import com.translive.app.data.model.Language
import com.translive.app.engine.OcrLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSubtitleTrackerTest {

    private fun rectOf(left: Int, top: Int, right: Int, bottom: Int): Rect =
        Rect().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }

    @Test
    fun testIoUCalculation() {
        val r1 = rectOf(0, 0, 100, 100) // Area 10000
        val r2 = rectOf(50, 0, 150, 100) // Area 10000, Overlap = 50 * 100 = 5000, Union = 15000
        val iou = SpatioTemporalSubtitleTracker.calculateIoU(r1, r2)
        assertEquals(5000f / 15000f, iou, 0.001f)

        val disjoint = rectOf(200, 200, 300, 300)
        assertEquals(0f, SpatioTemporalSubtitleTracker.calculateIoU(r1, disjoint), 0.001f)
    }

    @Test
    fun testLevenshteinFuzzySimilarity() {
        val simExact = SpatioTemporalSubtitleTracker.calculateFuzzySimilarity("Welcome to Tokyo", "Welcome to Tokyo")
        assertEquals(1.0f, simExact, 0.001f)

        // Slight OCR glitch ("W3lcome to Tokyo")
        val simGlitch = SpatioTemporalSubtitleTracker.calculateFuzzySimilarity("Welcome to Tokyo", "W3lcome to Tokyo")
        assertTrue(simGlitch >= 0.90f)

        // Completely different strings
        val simDifferent = SpatioTemporalSubtitleTracker.calculateFuzzySimilarity("Exit Right", "Burger King")
        assertTrue(simDifferent < 0.25f)
    }

    @Test
    fun testTrackerMultiFrameStabilityAndJitterFilter() {
        val tracker = SpatioTemporalSubtitleTracker(
            iouThreshold = 0.35f,
            ttlFrames = 4,
            minHitsForStability = 2
        )

        // Frame 1: First appearance
        val f1 = listOf(OcrLine("Gate 42 Boarding", rectOf(10, 50, 300, 90)))
        val subs1 = tracker.update(f1, Language.ENGLISH, Language.RUSSIAN) { texts ->
            texts.map { "Выход 42 Посадка" }
        }
        assertEquals(1, subs1.size)
        assertFalse(subs1[0].isStable) // Not yet stable on frame 1

        // Frame 2: Same text with 2px camera tremor and slight OCR flicker ("Gate 42 B0arding")
        val f2 = listOf(OcrLine("Gate 42 B0arding", rectOf(12, 52, 302, 92)))
        val subs2 = tracker.update(f2, Language.ENGLISH, Language.RUSSIAN) { texts ->
            texts.map { "Выход 42 Посадка" }
        }
        assertEquals(1, subs2.size)
        assertTrue(subs2[0].isStable) // Promoted to stable!
        assertEquals("Gate 42 Boarding", subs2[0].originalText) // Retained clean text from frame 1
        assertEquals("Выход 42 Посадка", subs2[0].translatedText)

        // Frames 3..7: Missed frames (e.g. hand passes in front of camera)
        repeat(4) {
            tracker.update(emptyList(), Language.ENGLISH, Language.RUSSIAN) { it }
        }

        // Frame 8: Exceeded TTL -> track removed
        val subsDead = tracker.update(emptyList(), Language.ENGLISH, Language.RUSSIAN) { it }
        assertEquals(0, subsDead.size)
    }

    @Test
    fun testSubtitleModelsAndActions() {
        val initialStyle = SubtitleStyle(fontSizeSp = 16, backgroundOpacity = 0.85f, showOriginal = true)
        val line = SubtitleLine(
            id = "sub_1",
            originalText = "Flight Delayed",
            translatedText = "Рейс задержан",
            sourceLanguage = Language.ENGLISH,
            targetLanguage = Language.RUSSIAN,
            boundingBox = rectOf(10, 20, 100, 60),
            isStable = true
        )

        val state = LiveSubtitleUiState(
            isSubtitleModeActive = true,
            subtitles = listOf(line),
            isPaused = false,
            style = initialStyle,
            activeTrackCount = 1
        )

        assertTrue(state.isSubtitleModeActive)
        assertEquals(1, state.subtitles.size)
        assertEquals("Рейс задержан", state.subtitles[0].translatedText)
        assertEquals(16, state.style.fontSizeSp)
    }
}
