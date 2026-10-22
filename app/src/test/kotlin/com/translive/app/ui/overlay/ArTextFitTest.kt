package com.translive.app.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArTextFitTest {

    @Test
    fun initialTextSize_fitsSingleLineInsideBlock() {
        val size = ArTextFit.initialTextSizePx(
            sourceHeightPx = 48f,
            estimatedLines = 1,
            minTextSizePx = 9f,
            maxTextSizePx = 22f
        )
        assertTrue(size in 9f..22f)
        assertTrue(size <= 48f)
    }

    @Test
    fun initialTextSize_isClampedForTinyAndHugeBlocks() {
        val tiny = ArTextFit.initialTextSizePx(4f, 1, 9f, 22f)
        val huge = ArTextFit.initialTextSizePx(400f, 1, 9f, 22f)
        assertEquals(9f, tiny, 0.001f)
        assertEquals(22f, huge, 0.001f)
    }

    @Test
    fun maxLinesFor_neverReturnsZero() {
        assertEquals(1, ArTextFit.maxLinesFor(0f, 20f))
        assertEquals(1, ArTextFit.maxLinesFor(10f, 20f))
        assertEquals(2, ArTextFit.maxLinesFor(40f, 20f))
    }

    @Test
    fun nextSmallerSize_respectsFloorAndShrinks() {
        assertEquals(9f, ArTextFit.nextSmallerSize(9f, 9f), 0.001f)
        assertEquals(9f, ArTextFit.nextSmallerSize(9.5f, 9f), 0.001f)
        assertTrue(ArTextFit.nextSmallerSize(20f, 9f) < 20f)
    }

    @Test
    fun truncation_onlyWhenLineCountExceedsBudget() {
        assertFalse(ArTextFit.needsTruncation(2, 3))
        assertTrue(ArTextFit.needsTruncation(4, 3))
        assertFalse(ArTextFit.needsTruncation(1, 0))
    }
}
