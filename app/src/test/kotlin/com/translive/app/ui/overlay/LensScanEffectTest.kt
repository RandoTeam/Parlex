package com.translive.app.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class ScanViewport(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

object LensScanMath {

    enum class ScanMode {
        LINEAR_LOOP,
        PING_PONG
    }

    data class BeamBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val centerY: Float,
        val height: Float
    ) {
        fun intersects(target: ScanViewport): Boolean {
            return left < target.right && right > target.left &&
                   top < target.bottom && bottom > target.top
        }
    }

    fun calculateProgress(
        elapsedMs: Long,
        durationMs: Long,
        mode: ScanMode = ScanMode.LINEAR_LOOP
    ): Float {
        if (durationMs <= 0L || elapsedMs <= 0L) return 0.0f
        val cyclePosition = (elapsedMs % durationMs).toFloat() / durationMs.toFloat()
        return when (mode) {
            ScanMode.LINEAR_LOOP -> cyclePosition.coerceIn(0.0f, 1.0f)
            ScanMode.PING_PONG -> {
                val pingPongFraction = (elapsedMs % (2 * durationMs)).toFloat() / durationMs.toFloat()
                if (pingPongFraction <= 1.0f) pingPongFraction else (2.0f - pingPongFraction).coerceIn(0.0f, 1.0f)
            }
        }
    }

    fun calculateBeamBounds(
        progress: Float,
        viewport: ScanViewport,
        beamHeight: Float
    ): BeamBounds {
        val clampedProgress = progress.coerceIn(0.0f, 1.0f)
        val centerY = viewport.top + (viewport.height * clampedProgress)
        val halfHeight = beamHeight / 2.0f

        val top = max(viewport.top, centerY - halfHeight)
        val bottom = min(viewport.bottom, centerY + halfHeight)
        val effectiveHeight = max(0.0f, bottom - top)

        return BeamBounds(
            left = viewport.left,
            top = top,
            right = viewport.right,
            bottom = bottom,
            centerY = centerY,
            height = effectiveHeight
        )
    }

    fun lerpColor(startColor: Int, endColor: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0.0f, 1.0f)
        val startA = (startColor ushr 24) and 0xFF
        val startR = (startColor ushr 16) and 0xFF
        val startG = (startColor ushr 8) and 0xFF
        val startB = startColor and 0xFF

        val endA = (endColor ushr 24) and 0xFF
        val endR = (endColor ushr 16) and 0xFF
        val endG = (endColor ushr 8) and 0xFF
        val endB = endColor and 0xFF

        val a = (startA + ((endA - startA) * f)).toInt()
        val r = (startR + ((endR - startR) * f)).toInt()
        val g = (startG + ((endG - startG) * f)).toInt()
        val b = (startB + ((endB - startB) * f)).toInt()

        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun calculateBeamAlpha(
        pointY: Float,
        centerY: Float,
        halfHeight: Float,
        peakAlpha: Float = 1.0f
    ): Float {
        if (halfHeight <= 0.0f) return 0.0f
        val distance = abs(pointY - centerY)
        if (distance >= halfHeight) return 0.0f
        val normalized = 1.0f - (distance / halfHeight)
        return (peakAlpha * normalized).coerceIn(0.0f, 1.0f)
    }
}

class LensScanEffectTest {

    private val delta = 0.0001f

    @Test
    fun testProgressLinearLoopStartAndEnd() {
        val durationMs = 2000L
        assertEquals(0.0f, LensScanMath.calculateProgress(0L, durationMs), delta)
        assertEquals(0.25f, LensScanMath.calculateProgress(500L, durationMs), delta)
        assertEquals(0.50f, LensScanMath.calculateProgress(1000L, durationMs), delta)
        assertEquals(0.75f, LensScanMath.calculateProgress(1500L, durationMs), delta)
        assertEquals(0.0f, LensScanMath.calculateProgress(2000L, durationMs), delta)
    }

    @Test
    fun testBeamBoundsAtMidpoint() {
        val viewport = ScanViewport(0.0f, 0.0f, 1080.0f, 1920.0f)
        val beamHeight = 100.0f
        val progress = 0.5f

        val bounds = LensScanMath.calculateBeamBounds(progress, viewport, beamHeight)

        assertEquals(0.0f, bounds.left, delta)
        assertEquals(1080.0f, bounds.right, delta)
        assertEquals(960.0f, bounds.centerY, delta)
        assertEquals(910.0f, bounds.top, delta)
        assertEquals(1010.0f, bounds.bottom, delta)
        assertEquals(100.0f, bounds.height, delta)
    }

    @Test
    fun testColorInterpolationPureChannels() {
        val red = (0xFF shl 24) or (0xFF shl 16) or (0x00 shl 8) or 0x00
        val blue = (0xFF shl 24) or (0x00 shl 16) or (0x00 shl 8) or 0xFF

        val start = LensScanMath.lerpColor(red, blue, 0.0f)
        val mid = LensScanMath.lerpColor(red, blue, 0.5f)
        val end = LensScanMath.lerpColor(red, blue, 1.0f)

        assertEquals(red, start)
        assertEquals(blue, end)

        val midR = (mid ushr 16) and 0xFF
        val midB = mid and 0xFF
        val midA = (mid ushr 24) and 0xFF

        assertEquals(255, midA)
        assertEquals(127, midR)
        assertEquals(127, midB)
    }
}
