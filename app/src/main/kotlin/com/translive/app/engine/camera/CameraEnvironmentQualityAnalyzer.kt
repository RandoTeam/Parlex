package com.translive.app.engine.camera

import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * High-performance, zero-allocation real-time environment and optical quality analyzer
 * for camera streams.
 *
 * Inspects the raw Y-plane buffer (luminance) using a subsampled grid:
 * 1. Low-light detection (<45 luma) -> triggers flash auto-prompt.
 * 2. Motion / hand-shake detection via Luma SAD -> stabilizes OCR triggers.
 * 3. Sharpness / edge contrast -> flags soft focus / motion blur.
 */
class CameraEnvironmentQualityAnalyzer(
    private val gridDim: Int = 32,
    private val lowLightLumaThreshold: Float = 45f,
    private val motionThreshold: Float = 0.035f,
    private val softFocusThreshold: Float = 6.5f
) {
    private val sampleCount = gridDim * gridDim
    private val prevSamples = FloatArray(sampleCount)
    private val currSamples = FloatArray(sampleCount)
    private var hasPrev = false
    private var lastStableMs = 0L

    data class AnalysisOutput(
        val isLowLight: Boolean,
        val averageLuma: Float,
        val isShaking: Boolean,
        val isSoftFocus: Boolean,
        val sharpnessScore: Float,
        val canTriggerOcr: Boolean
    )

    /**
     * Analyzes raw Y-plane buffer of CameraX ImageProxy without converting to Bitmap.
     */
    @androidx.camera.core.ExperimentalGetImage
    fun analyze(imageProxy: ImageProxy, timestampMs: Long = System.currentTimeMillis()): AnalysisOutput {
        val plane = imageProxy.planes.getOrNull(0) ?: return AnalysisOutput(
            isLowLight = false,
            averageLuma = 128f,
            isShaking = false,
            isSoftFocus = false,
            sharpnessScore = 10f,
            canTriggerOcr = true
        )

        val buffer = plane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        return analyzeBuffer(
            buffer = buffer,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
            timestampMs = timestampMs
        )
    }

    /**
     * Direct buffer analyzer suitable for testing and decoupled invocation.
     */
    fun analyzeBuffer(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        timestampMs: Long = System.currentTimeMillis()
    ): AnalysisOutput {
        if (width <= 0 || height <= 0) {
            return AnalysisOutput(false, 128f, false, false, 10f, true)
        }

        val stepX = (width / gridDim).coerceAtLeast(1)
        val stepY = (height / gridDim).coerceAtLeast(1)

        var lumaSum = 0L
        var idx = 0
        var edgeSum = 0L

        buffer.rewind()

        for (gy in 0 until gridDim) {
            val y = (gy * stepY).coerceAtMost(height - 1)
            val rowOffset = y * rowStride
            var prevLuma = -1
            for (gx in 0 until gridDim) {
                val x = (gx * stepX).coerceAtMost(width - 1)
                val offset = rowOffset + x * pixelStride
                if (offset < buffer.limit()) {
                    val luma = buffer.get(offset).toInt() and 0xFF
                    currSamples[idx++] = luma / 255f
                    lumaSum += luma

                    if (prevLuma >= 0) {
                        edgeSum += abs(luma - prevLuma)
                    }
                    prevLuma = luma
                } else {
                    currSamples[idx++] = 0.5f
                }
            }
        }

        val avgLuma = lumaSum.toFloat() / sampleCount
        val sharpness = edgeSum.toFloat() / (gridDim * (gridDim - 1)).coerceAtLeast(1)
        val isLowLight = avgLuma < lowLightLumaThreshold
        val isSoftFocus = sharpness < softFocusThreshold

        var motionDelta = 0f
        if (hasPrev) {
            var diff = 0f
            for (i in 0 until sampleCount) {
                diff += abs(currSamples[i] - prevSamples[i])
            }
            motionDelta = diff / sampleCount
        }
        System.arraycopy(currSamples, 0, prevSamples, 0, sampleCount)
        hasPrev = true

        val isShaking = motionDelta > motionThreshold
        if (isShaking) {
            lastStableMs = timestampMs
        }
        val isStable = (timestampMs - lastStableMs) >= 180L
        val canTriggerOcr = isStable && !isSoftFocus

        return AnalysisOutput(
            isLowLight = isLowLight,
            averageLuma = avgLuma,
            isShaking = isShaking,
            isSoftFocus = isSoftFocus,
            sharpnessScore = sharpness,
            canTriggerOcr = canTriggerOcr
        )
    }

    /**
     * Direct float array analyzer for fast unit testing.
     */
    fun analyzeSampleArray(samples: FloatArray, timestampMs: Long = System.currentTimeMillis()): AnalysisOutput {
        val count = samples.size
        var lumaSum = 0f
        var edgeSum = 0f

        for (i in 0 until count) {
            val luma = samples[i] * 255f
            lumaSum += luma
            if (i > 0 && i % gridDim != 0) {
                edgeSum += abs(luma - (samples[i - 1] * 255f))
            }
        }

        val avgLuma = lumaSum / count.coerceAtLeast(1)
        val sharpness = edgeSum / (gridDim * (gridDim - 1)).coerceAtLeast(1)
        val isLowLight = avgLuma < lowLightLumaThreshold
        val isSoftFocus = sharpness < softFocusThreshold

        var motionDelta = 0f
        if (hasPrev) {
            var diff = 0f
            for (i in 0 until count.coerceAtMost(sampleCount)) {
                diff += abs(samples[i] - prevSamples[i])
            }
            motionDelta = diff / count
        }
        val copyLen = count.coerceAtMost(sampleCount)
        System.arraycopy(samples, 0, prevSamples, 0, copyLen)
        hasPrev = true

        val isShaking = motionDelta > motionThreshold
        if (isShaking) {
            lastStableMs = timestampMs
        }
        val isStable = (timestampMs - lastStableMs) >= 180L

        return AnalysisOutput(
            isLowLight = isLowLight,
            averageLuma = avgLuma,
            isShaking = isShaking,
            isSoftFocus = isSoftFocus,
            sharpnessScore = sharpness,
            canTriggerOcr = isStable && !isSoftFocus
        )
    }

    fun reset() {
        hasPrev = false
        lastStableMs = 0L
    }
}
