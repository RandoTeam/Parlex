package com.translive.app.engine

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeyframeMotionDetectorTest {

    @Test
    fun `initial frame enters stabilizing state`() {
        val detector = KeyframeMotionDetector(gridDimension = 4)
        val initialSamples = FloatArray(16) { 0.5f }

        val result = detector.processSampleArray(initialSamples, timestampMs = 1000L)
        assertTrue(result is KeyframeMotionDetector.DetectionResult.Stabilizing)
    }

    @Test
    fun `static frames trigger keyframe after stableDurationMs threshold`() {
        val detector = KeyframeMotionDetector(
            motionThreshold = 0.02f,
            stableDurationMs = 300L,
            gridDimension = 4
        )
        val staticSamples = FloatArray(16) { 0.5f }

        // t = 0ms: initial frame
        val r0 = detector.processSampleArray(staticSamples, timestampMs = 1000L)
        assertTrue(r0 is KeyframeMotionDetector.DetectionResult.Stabilizing)

        // t = 100ms: still stabilizing
        val r1 = detector.processSampleArray(staticSamples, timestampMs = 1100L)
        assertTrue(r1 is KeyframeMotionDetector.DetectionResult.Stabilizing)
        assertEquals(100L, (r1 as KeyframeMotionDetector.DetectionResult.Stabilizing).stableForMs)

        // t = 300ms: keyframe should trigger!
        val r2 = detector.processSampleArray(staticSamples, timestampMs = 1300L)
        assertTrue(r2 is KeyframeMotionDetector.DetectionResult.KeyframeTriggered)

        // t = 400ms: should remain in StaticHold without re-triggering keyframe
        val r3 = detector.processSampleArray(staticSamples, timestampMs = 1400L)
        assertTrue(r3 is KeyframeMotionDetector.DetectionResult.StaticHold)
    }

    @Test
    fun `motion resets stability timer and allows subsequent keyframe on rest`() {
        val detector = KeyframeMotionDetector(
            motionThreshold = 0.05f,
            stableDurationMs = 300L,
            gridDimension = 4
        )
        val frameA = FloatArray(16) { 0.2f }
        val frameB = FloatArray(16) { 0.8f } // Significant change (delta = 0.6)

        // Start static
        detector.processSampleArray(frameA, timestampMs = 1000L)
        detector.processSampleArray(frameA, timestampMs = 1300L) // Triggers keyframe

        // Motion occurs
        val rMotion = detector.processSampleArray(frameB, timestampMs = 1400L)
        assertTrue(rMotion is KeyframeMotionDetector.DetectionResult.MotionDetected)

        // Settle down on new content
        val rSettle1 = detector.processSampleArray(frameB, timestampMs = 1500L)
        assertTrue(rSettle1 is KeyframeMotionDetector.DetectionResult.Stabilizing)

        val rKeyframe2 = detector.processSampleArray(frameB, timestampMs = 1800L)
        assertTrue(rKeyframe2 is KeyframeMotionDetector.DetectionResult.KeyframeTriggered)
    }
}
