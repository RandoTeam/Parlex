package com.translive.app.engine.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ColorSamplingAndLuminance {

    private const val CORNER_SAMPLE_PATCH_SIZE = 3
    private const val PERIMETER_PAD_PX = 2

    /**
     * Samples the surrounding border ring and corners of a bounding box on the original bitmap
     * to avoid sampling text character glyphs inside.
     */
    fun sampleBoxBackgroundAndContrast(bitmap: Bitmap, box: Rect): SampledBackground {
        val width = bitmap.width
        val height = bitmap.height
        val padded = Rect(
            (box.left - PERIMETER_PAD_PX).coerceIn(0, width - 1),
            (box.top - PERIMETER_PAD_PX).coerceIn(0, height - 1),
            (box.right + PERIMETER_PAD_PX).coerceIn(0, width - 1),
            (box.bottom + PERIMETER_PAD_PX).coerceIn(0, height - 1)
        )

        val topSamples = mutableListOf<Int>()
        val bottomSamples = mutableListOf<Int>()

        // 1. Four corner patches
        samplePatch(bitmap, padded.left, padded.top, topSamples)
        samplePatch(bitmap, padded.right - CORNER_SAMPLE_PATCH_SIZE, padded.top, topSamples)
        samplePatch(bitmap, padded.left, padded.bottom - CORNER_SAMPLE_PATCH_SIZE, bottomSamples)
        samplePatch(bitmap, padded.right - CORNER_SAMPLE_PATCH_SIZE, padded.bottom - CORNER_SAMPLE_PATCH_SIZE, bottomSamples)

        // 2. Perimeter edge midpoints
        val midX = (padded.left + padded.right) / 2
        val midY = (padded.top + padded.bottom) / 2
        samplePatch(bitmap, midX, padded.top, topSamples)
        samplePatch(bitmap, midX, padded.bottom - CORNER_SAMPLE_PATCH_SIZE, bottomSamples)
        samplePatch(bitmap, padded.left, midY, topSamples)
        samplePatch(bitmap, padded.right - CORNER_SAMPLE_PATCH_SIZE, midY, bottomSamples)

        val topColor = if (topSamples.isNotEmpty()) calculateTrimmedMeanColor(topSamples) else Color.WHITE
        val bottomColor = if (bottomSamples.isNotEmpty()) calculateTrimmedMeanColor(bottomSamples) else topColor

        val avgLuma = (calculateLuminance(topColor) + calculateLuminance(bottomColor)) / 2f
        val isDark = avgLuma <= 128f

        val textColor = if (isDark) Color.rgb(250, 250, 250) else Color.rgb(18, 18, 18)
        val strokeColor = if (isDark) Color.argb(160, 0, 0, 0) else Color.argb(160, 255, 255, 255)

        return SampledBackground(
            topColor = topColor,
            bottomColor = bottomColor,
            primaryTextColor = textColor,
            strokeColor = strokeColor,
            isDarkBackground = isDark
        )
    }

    private fun samplePatch(bitmap: Bitmap, startX: Int, startY: Int, outSamples: MutableList<Int>) {
        val x0 = startX.coerceIn(0, bitmap.width - 1)
        val y0 = startY.coerceIn(0, bitmap.height - 1)
        val x1 = (x0 + CORNER_SAMPLE_PATCH_SIZE).coerceAtMost(bitmap.width)
        val y1 = (y0 + CORNER_SAMPLE_PATCH_SIZE).coerceAtMost(bitmap.height)

        for (y in y0 until y1) {
            for (x in x0 until x1) {
                outSamples.add(bitmap.getPixel(x, y))
            }
        }
    }

    /**
     * Discards upper and lower 15% outliers in luminance to reject noise/shadows.
     */
    private fun calculateTrimmedMeanColor(pixels: List<Int>): Int {
        if (pixels.isEmpty()) return Color.DKGRAY
        val sortedByLuma = pixels.sortedBy { calculateLuminance(it) }
        val trimCount = (pixels.size * 0.15f).roundToInt()
        val trimmed = if (pixels.size > 8) {
            sortedByLuma.subList(trimCount, pixels.size - trimCount)
        } else {
            sortedByLuma
        }

        var r = 0L; var g = 0L; var b = 0L
        for (px in trimmed) {
            r += (px shr 16) and 0xFF
            g += (px shr 8) and 0xFF
            b += px and 0xFF
        }
        val count = trimmed.size
        val avgR = (r / count).toInt()
        val avgG = (g / count).toInt()
        val avgB = (b / count).toInt()
        return (0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB
    }

    fun calculateLuminance(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299f * r + 0.587f * g + 0.114f * b)
    }
}
