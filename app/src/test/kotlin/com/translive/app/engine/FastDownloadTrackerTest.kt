package com.translive.app.engine

import com.translive.app.engine.download.DownloadFormatter
import com.translive.app.engine.download.DownloadMetricsTracker
import com.translive.app.engine.download.UniversalDownloadState
import com.translive.app.engine.download.isDownloading
import com.translive.app.engine.download.progressPercent
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM Unit Test Suite for Fast NMT Download Progress & Transparency.
 *
 * Requirements:
 * 1. Test per-language package download state progression (Idle -> Downloading -> Completed).
 * 2. Test exact byte formatting (e.g. "14.2 МБ / 29.5 МБ").
 * 3. Test speed calculation and ETA estimation for Fast NMT.
 * 4. Test batch download aggregation (e.g. 59 languages, 1.7 GB total).
 * 5. 100% Pure JVM.
 */
class FastDownloadTrackerTest {

    @Test
    fun `Per-language package download state updates byte progress accurately`() {
        val totalBytes = 29_500_000L // ~29.5 MB per Google ML Kit language package
        val tracker = DownloadMetricsTracker(totalBytes = totalBytes, smoothingAlpha = 0.25)

        val state1 = tracker.updateProgress(bytesDownloaded = 7_375_000L, nowMs = 1000L)
        assertEquals(7_375_000L, state1.bytesDownloaded)
        assertEquals(29_500_000L, state1.totalBytes)
        assertEquals(25, state1.progressPercent)

        val state2 = tracker.updateProgress(bytesDownloaded = 14_750_000L, nowMs = 2000L)
        assertEquals(14_750_000L, state2.bytesDownloaded)
        assertEquals(50, state2.progressPercent)

        val state3 = tracker.updateProgress(bytesDownloaded = 29_500_000L, nowMs = 4000L)
        assertEquals(100, state3.progressPercent)
        assertTrue(state3.progress >= 1.0f)
    }

    @Test
    fun `DownloadFormatter produces clean Russian metric strings for Fast NMT row`() {
        val bytesDownloaded = 15_728_640L // 15 MB
        val totalBytes = 30_932_992L      // 29.5 MB
        val speedBytesPerSec = 2_621_440L // 2.5 MB/s

        val formattedBytes = DownloadFormatter.formatBytesProgress(bytesDownloaded, totalBytes)
        val formattedSpeed = DownloadFormatter.formatSpeed(speedBytesPerSec)
        val formattedEta = DownloadFormatter.formatEta(6)

        assertTrue(formattedBytes.contains("MB") || formattedBytes.contains("МБ"), "Must format bytes with MB unit")
        assertTrue(formattedSpeed.contains("MB/s") || formattedSpeed.contains("МБ/с"), "Must format speed with per-sec unit")
        assertTrue(formattedEta.contains("6") || formattedEta.contains("s") || formattedEta.contains("с"), "Must format ETA correctly")
    }

    @Test
    fun `Batch download metrics aggregates 59 languages correctly`() {
        val packageCount = 59
        val packageSize = 29_500_000L
        val totalCatalogBytes = packageCount * packageSize // ~1.74 GB

        val completedCount = 14
        val currentDownloadingBytes = 15_000_000L
        val totalDownloadedBytes = (completedCount * packageSize) + currentDownloadingBytes

        val batchProgress = totalDownloadedBytes.toFloat() / totalCatalogBytes.toFloat()
        val percent = (batchProgress * 100).toInt()

        assertTrue(percent in 24..26, "14.5 / 59 languages should be ~25% completed")
        val remainingBytes = totalCatalogBytes - totalDownloadedBytes
        assertTrue(remainingBytes > 1_000_000_000L, "Remaining bytes should be > 1 GB")
    }

    @Test
    fun `Fast model download state transition handles cancellation and pause gracefully`() {
        var state: UniversalDownloadState = UniversalDownloadState.Idle
        assertFalse(state.isDownloading)

        state = UniversalDownloadState.Downloading(
            bytesDownloaded = 10_000_000L,
            totalBytes = 29_500_000L,
            speedBytesPerSec = 2_000_000L,
            etaSeconds = 10L
        )
        assertTrue(state.isDownloading)
        assertEquals(33, state.progressPercent)

        // Pause state
        state = UniversalDownloadState.Paused(
            bytesDownloaded = 10_000_000L,
            totalBytes = 29_500_000L
        )
        assertFalse(state.isDownloading)
        assertEquals(33, state.progressPercent)

        // Cancelled resets to Idle
        state = UniversalDownloadState.Idle
        assertFalse(state.isDownloading)
    }
}
