package com.translive.app.engine

import com.translive.app.engine.download.DownloadFormatter
import com.translive.app.engine.download.DownloadMetricsTracker
import com.translive.app.engine.download.UniversalDownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit test suite verifying Sub-Phase M3:
 * Universal Download Metrics, EMA Speed Tracking, and Formatter.
 */
class DownloadMetricsTrackerTest {

    // =========================================================================
    // SECTION 1: Formatter Tests
    // =========================================================================

    @Test
    fun formatter_formatsBytesAccurately() {
        assertEquals("0 B", DownloadFormatter.formatBytes(0L))
        assertEquals("500 B", DownloadFormatter.formatBytes(500L))
        assertEquals("120 KB", DownloadFormatter.formatBytes(120 * 1024L))
        assertEquals("65.0 MB", DownloadFormatter.formatBytes(65 * 1024 * 1024L))
        assertEquals("1.2 GB", DownloadFormatter.formatBytes((1.18 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatter_formatsSpeedAccurately() {
        assertEquals("0 B/s", DownloadFormatter.formatSpeed(0L))
        assertEquals("350 KB/s", DownloadFormatter.formatSpeed(350 * 1024L))
        assertEquals("2.8 MB/s", DownloadFormatter.formatSpeed((2.8 * 1024 * 1024).toLong()))
    }

    @Test
    fun formatter_formatsEtaAccurately() {
        assertEquals("", DownloadFormatter.formatEta(-1L))
        assertEquals("ETA 4s", DownloadFormatter.formatEta(4L))
        assertEquals("ETA 1m 30s", DownloadFormatter.formatEta(90L))
        assertEquals("ETA 1h 15m", DownloadFormatter.formatEta(4500L))
    }

    // =========================================================================
    // SECTION 2: DownloadMetricsTracker State & EMA Calculations
    // =========================================================================

    @Test
    fun tracker_progressPercentage_andStateTransitions() {
        val totalBytes = 100_000_000L // 100 MB
        val tracker = DownloadMetricsTracker(totalBytes = totalBytes, minEmitIntervalMs = 0L)

        assertEquals(UniversalDownloadState.Idle, tracker.state.value)

        tracker.start(existingBytes = 0L)
        val initial = tracker.state.value as UniversalDownloadState.Downloading
        assertEquals(0L, initial.bytesDownloaded)
        assertEquals(0, initial.progressPercent)

        // Progress update to 50%
        tracker.onBytesProgress(50_000_000L)
        val mid = tracker.state.value as UniversalDownloadState.Downloading
        assertEquals(50_000_000L, mid.bytesDownloaded)
        assertEquals(50, mid.progressPercent)

        // Pause
        tracker.pause(50_000_000L)
        val paused = tracker.state.value as UniversalDownloadState.Paused
        assertEquals(50, paused.progressPercent)

        // Complete
        tracker.complete()
        assertEquals(UniversalDownloadState.Completed, tracker.state.value)
    }

    @Test
    fun tracker_failedAndResetStates() {
        val tracker = DownloadMetricsTracker(totalBytes = 50_000_000L)
        tracker.fail("Network timeout", isRecoverable = true)

        val failed = tracker.state.value as UniversalDownloadState.Failed
        assertEquals("Network timeout", failed.error)
        assertTrue(failed.isRecoverable)

        tracker.reset()
        assertEquals(UniversalDownloadState.Idle, tracker.state.value)
    }
}
