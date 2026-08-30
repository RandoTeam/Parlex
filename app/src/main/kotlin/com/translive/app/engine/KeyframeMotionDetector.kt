package com.translive.app.engine

import android.graphics.Bitmap
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Real-time motion and stability detector for screen frame streams.
 *
 * Employs subsampled Luminance Sum of Absolute Differences (SAD) to evaluate
 * whether screen content is actively scrolling/animating or has remained static
 * long enough (e.g. 300ms) to warrant triggering an OCR/NMT keyframe cycle.
 */
class KeyframeMotionDetector(
    val motionThreshold: Float = 0.025f,     // 2.5% average pixel intensity change
    val stableDurationMs: Long = 300L,       // Required static duration before keyframe
    val gridDimension: Int = 32              // 32x32 sample grid = 1024 sample points
) {

    private val sampleCount = gridDimension * gridDimension
    private val previousSamples = FloatArray(sampleCount)
    private val currentSamples = FloatArray(sampleCount)
    private var hasPreviousFrame = false

    private var stableSinceMs: Long = 0L
    private var keyframeEmittedForCurrentHold: Boolean = false

    sealed interface DetectionResult {
        data object MotionDetected : DetectionResult
        data class Stabilizing(val stableForMs: Long) : DetectionResult
        data class KeyframeTriggered(val timestampMs: Long, val stableForMs: Long) : DetectionResult
        data object StaticHold : DetectionResult
    }

    /**
     * Process an incoming [Bitmap] and determine if a keyframe should be triggered.
     */
    fun processFrame(bitmap: Bitmap, timestampMs: Long = System.currentTimeMillis()): DetectionResult {
        extractLumaSamplesFromBitmap(bitmap, currentSamples)
        return evaluateSamples(timestampMs)
    }

    /**
     * Process an incoming raw pixel buffer (RGBA or single-channel Luma).
     */
    fun processBuffer(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int = 4,
        timestampMs: Long = System.currentTimeMillis()
    ): DetectionResult {
        extractLumaSamplesFromBuffer(buffer, width, height, rowStride, pixelStride, currentSamples)
        return evaluateSamples(timestampMs)
    }

    /**
     * Process an explicit sample array directly (useful for testing or pre-extracted grids).
     */
    fun processSampleArray(samples: FloatArray, timestampMs: Long = System.currentTimeMillis()): DetectionResult {
        require(samples.size == sampleCount) { "Sample array size must match gridDimension^2 ($sampleCount)" }
        System.arraycopy(samples, 0, currentSamples, 0, sampleCount)
        return evaluateSamples(timestampMs)
    }

    private fun evaluateSamples(timestampMs: Long): DetectionResult {
        if (!hasPreviousFrame) {
            System.arraycopy(currentSamples, 0, previousSamples, 0, sampleCount)
            hasPreviousFrame = true
            stableSinceMs = timestampMs
            keyframeEmittedForCurrentHold = false
            return DetectionResult.Stabilizing(0L)
        }

        var totalDifference = 0f
        for (i in 0 until sampleCount) {
            totalDifference += abs(currentSamples[i] - previousSamples[i])
        }
        val averageDelta = totalDifference / sampleCount
        System.arraycopy(currentSamples, 0, previousSamples, 0, sampleCount)

        if (averageDelta > motionThreshold) {
            // Screen is actively changing / scrolling
            stableSinceMs = 0L
            keyframeEmittedForCurrentHold = false
            return DetectionResult.MotionDetected
        }

        // Screen is static
        if (stableSinceMs == 0L) {
            stableSinceMs = timestampMs
        }

        val stableDuration = timestampMs - stableSinceMs

        return if (stableDuration >= stableDurationMs) {
            if (!keyframeEmittedForCurrentHold) {
                keyframeEmittedForCurrentHold = true
                DetectionResult.KeyframeTriggered(timestampMs, stableDuration)
            } else {
                DetectionResult.StaticHold
            }
        } else {
            DetectionResult.Stabilizing(stableDuration)
        }
    }

    fun reset() {
        hasPreviousFrame = false
        stableSinceMs = 0L
        keyframeEmittedForCurrentHold = false
    }

    private fun extractLumaSamplesFromBitmap(bitmap: Bitmap, output: FloatArray) {
        val width = bitmap.width
        val height = bitmap.height
        val stepX = width / gridDimension
        val stepY = height / gridDimension

        var idx = 0
        for (gy in 0 until gridDimension) {
            val y = (gy * stepY).coerceAtMost(height - 1)
            for (gx in 0 until gridDimension) {
                val x = (gx * stepX).coerceAtMost(width - 1)
                val pixel = bitmap.getPixel(x, y)
                // Standard ITU-R BT.601 Luma formula: 0.299R + 0.587G + 0.114B
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                output[idx++] = luma
            }
        }
    }

    private fun extractLumaSamplesFromBuffer(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        output: FloatArray
    ) {
        buffer.rewind()
        val stepX = width / gridDimension
        val stepY = height / gridDimension

        var idx = 0
        for (gy in 0 until gridDimension) {
            val y = (gy * stepY).coerceAtMost(height - 1)
            val rowOffset = y * rowStride
            for (gx in 0 until gridDimension) {
                val x = (gx * stepX).coerceAtMost(width - 1)
                val pixelOffset = rowOffset + x * pixelStride
                if (pixelOffset + 2 < buffer.limit()) {
                    val r = buffer.get(pixelOffset).toInt() and 0xFF
                    val g = buffer.get(pixelOffset + 1).toInt() and 0xFF
                    val b = buffer.get(pixelOffset + 2).toInt() and 0xFF
                    val luma = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                    output[idx++] = luma
                } else {
                    output[idx++] = 0f
                }
            }
        }
    }
}
