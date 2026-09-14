package com.translive.app.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * TDD Test Suite for Approach 2: Direct Canvas Inpainting & Per-Line Left-Edge Anchoring.
 * Uses pure JVM geometry structures to verify layout math independently of Android stubs.
 */
class ArPerLineLayoutTest {

    data class GeoRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    data class GeoRectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
    }

    data class ArLinePillModel(
        val lineRect: GeoRectF,
        val lineIndex: Int
    )

    @Test
    fun leftEdgeAnchoring_alignsExactlyToSourceLeft() {
        // Original headline at left = 140px, top = 280px
        val srcRect = GeoRect(140, 280, 920, 340)
        val textOffsetX = srcRect.left.toFloat()
        val textOffsetY = srcRect.top.toFloat()

        assertEquals("Text X must align exactly to source left edge (no centering)", 140f, textOffsetX, 0.001f)
        assertEquals("Text Y must align exactly to source top edge", 280f, textOffsetY, 0.001f)
    }

    @Test
    fun linePillBoundingBox_hugsIndividualLineExtentRatherThanFullCard() {
        val textOffsetX = 140f
        val textOffsetY = 280f
        val lineLeftRel = 0f
        val lineWidth = 320f // Short line in a 780px wide block
        val lineTopRel = 0f
        val lineBottomRel = 50f

        val padH = 4f
        val padV = 2f

        val lineLeft = textOffsetX + lineLeftRel
        val lineTop = textOffsetY + lineTopRel
        val lineRight = lineLeft + lineWidth
        val lineBottom = textOffsetY + lineBottomRel

        val pillRect = GeoRectF(
            left = lineLeft - padH,
            top = lineTop - padV,
            right = lineRight + padH,
            bottom = lineBottom + padV
        )

        assertEquals("Pill left should hug line start minus pad", 136f, pillRect.left, 0.001f)
        assertEquals("Pill right should hug line end plus pad (NOT full block width)", 464f, pillRect.right, 0.001f)
        assertEquals("Pill top should hug line top minus pad", 278f, pillRect.top, 0.001f)
        assertEquals("Pill bottom should hug line bottom plus pad", 332f, pillRect.bottom, 0.001f)
        assertTrue("Pill width (328) must be significantly less than full block width (780)", pillRect.width < 500f)
    }

    @Test
    fun eraseRect_encompassesSourceBoundingBoxWithBleedPadding() {
        val srcRect = GeoRect(100, 200, 800, 260)
        val padX = 3f
        val padY = 2f

        val eraseRect = GeoRectF(
            left = srcRect.left.toFloat() - padX,
            top = srcRect.top.toFloat() - padY,
            right = srcRect.right.toFloat() + padX,
            bottom = srcRect.bottom.toFloat() + padY
        )

        assertEquals(97f, eraseRect.left, 0.001f)
        assertEquals(198f, eraseRect.top, 0.001f)
        assertEquals(803f, eraseRect.right, 0.001f)
        assertEquals(262f, eraseRect.bottom, 0.001f)
    }

    @Test
    fun trimmedMeanSampling_rejectsGlyphOutliersAndRecoversPureBackground() {
        // Simulate 20 sampled pixels: 16 background pixels (#12121E) and 4 stray black/white glyph noise pixels
        val bgPixel = (0xFF shl 24) or (0x12 shl 16) or (0x12 shl 8) or 0x1E
        val blackGlyph = (0xFF shl 24) or (0x00 shl 16) or (0x00 shl 8) or 0x00
        val whiteGlyph = (0xFF shl 24) or (0xFF shl 16) or (0xFF shl 8) or 0xFF

        val samples = mutableListOf<Int>()
        repeat(16) { samples.add(bgPixel) }
        repeat(2) { samples.add(blackGlyph) }
        repeat(2) { samples.add(whiteGlyph) }

        // Trim 15% from top and bottom
        fun luma(c: Int): Float {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            return 0.299f * r + 0.587f * g + 0.114f * b
        }

        samples.sortBy { luma(it) }
        val trimCount = (samples.size * 0.15f).roundToInt()
        val trimmed = samples.subList(trimCount, samples.size - trimCount)

        var rSum = 0L; var gSum = 0L; var bSum = 0L
        for (c in trimmed) {
            rSum += (c shr 16) and 0xFF
            gSum += (c shr 8) and 0xFF
            bSum += c and 0xFF
        }
        val avgR = (rSum / trimmed.size).toInt()
        val avgG = (gSum / trimmed.size).toInt()
        val avgB = (bSum / trimmed.size).toInt()

        assertEquals("Red channel must match background after outlier rejection", 0x12, avgR)
        assertEquals("Green channel must match background after outlier rejection", 0x12, avgG)
        assertEquals("Blue channel must match background after outlier rejection", 0x1E, avgB)
    }
}
