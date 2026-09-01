package com.translive.app.engine.download

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale
import kotlin.math.max

sealed interface UniversalDownloadState {
    data object Idle : UniversalDownloadState
    data object Queued : UniversalDownloadState
    data object Connecting : UniversalDownloadState

    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long = 0L,
        val etaSeconds: Long = -1L,
        val stepLabel: String? = null
    ) : UniversalDownloadState {
        val progress: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
        val progressPercent: Int get() = (progress * 100).toInt().coerceIn(0, 100)
    }

    data class Processing(
        val stage: String,
        val progress: Float = 0f
    ) : UniversalDownloadState

    data class Paused(
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : UniversalDownloadState {
        val progress: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
        val progressPercent: Int get() = (progress * 100).toInt().coerceIn(0, 100)
    }

    data object Completed : UniversalDownloadState
    data class Failed(val error: String, val isRecoverable: Boolean = true) : UniversalDownloadState
    data object Cancelled : UniversalDownloadState
}

val UniversalDownloadState.isDownloading: Boolean
    get() = this is UniversalDownloadState.Downloading || this is UniversalDownloadState.Processing || this is UniversalDownloadState.Connecting

val UniversalDownloadState.progressPercent: Int
    get() = when (this) {
        is UniversalDownloadState.Downloading -> progressPercent
        is UniversalDownloadState.Paused -> progressPercent
        is UniversalDownloadState.Completed -> 100
        else -> 0
    }

object DownloadFormatter {
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        bytes > 0L -> "$bytes B"
        else -> "0 B"
    }

    fun formatBytesProgress(downloaded: Long, total: Long): String =
        "${formatBytes(downloaded)} / ${formatBytes(total)}"

    fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1_048_576L -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024L -> String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0)
        bytesPerSec > 0L -> "$bytesPerSec B/s"
        else -> "0 B/s"
    }

    fun formatEta(etaSeconds: Long): String = when {
        etaSeconds < 0 -> ""
        etaSeconds < 60 -> "ETA ${etaSeconds}s"
        etaSeconds < 3600 -> "ETA ${etaSeconds / 60}m ${etaSeconds % 60}s"
        else -> "ETA ${etaSeconds / 3600}h ${(etaSeconds % 3600) / 60}m"
    }

    fun formatProgressSnippet(downloaded: Long, total: Long, speed: Long, eta: Long): String {
        val bytesStr = formatBytesProgress(downloaded, total)
        val percent = if (total > 0) " (${(downloaded * 100 / total)}%)" else ""
        val speedStr = if (speed > 0) " • ${formatSpeed(speed)}" else ""
        val etaStr = if (eta > 0) " • ${formatEta(eta)}" else ""
        return "$bytesStr$percent$speedStr$etaStr"
    }
}

class DownloadMetricsTracker(
    private val totalBytes: Long = -1L,
    private val smoothingAlpha: Double = 0.25,
    private val minEmitIntervalMs: Long = 250L
) {
    private val _state = MutableStateFlow<UniversalDownloadState>(UniversalDownloadState.Idle)
    val state: StateFlow<UniversalDownloadState> = _state.asStateFlow()

    private var lastEmitTimeMs = 0L
    private var lastEmitBytes = 0L
    private var smoothedSpeedBytesPerSec = 0.0
    private var currentTotalBytes = totalBytes

    fun start(existingBytes: Long = 0L, total: Long = totalBytes) {
        currentTotalBytes = total
        lastEmitTimeMs = System.currentTimeMillis()
        lastEmitBytes = existingBytes
        smoothedSpeedBytesPerSec = 0.0
        _state.value = UniversalDownloadState.Downloading(
            bytesDownloaded = existingBytes,
            totalBytes = currentTotalBytes,
            speedBytesPerSec = 0L,
            etaSeconds = -1L
        )
    }

    fun updateProgress(
        bytesDownloaded: Long,
        nowMs: Long = System.currentTimeMillis(),
        stepLabel: String? = null
    ): UniversalDownloadState.Downloading {
        val elapsed = nowMs - lastEmitTimeMs
        val instantSpeed = if (lastEmitTimeMs == 0L || elapsed <= 0) {
            0.0
        } else {
            ((bytesDownloaded - lastEmitBytes).toDouble() * 1000.0) / max(elapsed, 1L)
        }

        smoothedSpeedBytesPerSec = if (smoothedSpeedBytesPerSec <= 0.0) {
            instantSpeed
        } else {
            (smoothingAlpha * instantSpeed) + ((1.0 - smoothingAlpha) * smoothedSpeedBytesPerSec)
        }

        val speedLong = smoothedSpeedBytesPerSec.toLong()
        val etaSec = if (speedLong > 0 && currentTotalBytes > bytesDownloaded) {
            (currentTotalBytes - bytesDownloaded) / speedLong
        } else -1L

        lastEmitTimeMs = nowMs
        lastEmitBytes = bytesDownloaded

        val nextState = UniversalDownloadState.Downloading(
            bytesDownloaded = bytesDownloaded,
            totalBytes = currentTotalBytes,
            speedBytesPerSec = speedLong,
            etaSeconds = etaSec,
            stepLabel = stepLabel
        )
        _state.value = nextState
        return nextState
    }

    fun onBytesProgress(bytesDownloaded: Long, stepLabel: String? = null) {
        val now = System.currentTimeMillis()
        val elapsed = now - lastEmitTimeMs

        if (elapsed < minEmitIntervalMs && lastEmitTimeMs != 0L) return

        val instantSpeed = if (lastEmitTimeMs == 0L || elapsed <= 0) {
            0.0
        } else {
            ((bytesDownloaded - lastEmitBytes).toDouble() * 1000.0) / max(elapsed, 1L)
        }

        smoothedSpeedBytesPerSec = if (smoothedSpeedBytesPerSec <= 0.0) {
            instantSpeed
        } else {
            (smoothingAlpha * instantSpeed) + ((1.0 - smoothingAlpha) * smoothedSpeedBytesPerSec)
        }

        val speedLong = smoothedSpeedBytesPerSec.toLong()
        val etaSec = if (speedLong > 0 && currentTotalBytes > bytesDownloaded) {
            (currentTotalBytes - bytesDownloaded) / speedLong
        } else -1L

        lastEmitTimeMs = now
        lastEmitBytes = bytesDownloaded

        _state.value = UniversalDownloadState.Downloading(
            bytesDownloaded = bytesDownloaded,
            totalBytes = currentTotalBytes,
            speedBytesPerSec = speedLong,
            etaSeconds = etaSec,
            stepLabel = stepLabel
        )
    }

    fun setProcessing(stage: String, progress: Float = 0f) {
        _state.value = UniversalDownloadState.Processing(stage, progress)
    }

    fun pause(bytesDownloaded: Long) {
        _state.value = UniversalDownloadState.Paused(bytesDownloaded, currentTotalBytes)
    }

    fun complete() {
        _state.value = UniversalDownloadState.Completed
    }

    fun fail(message: String, isRecoverable: Boolean = true) {
        _state.value = UniversalDownloadState.Failed(message, isRecoverable)
    }

    fun cancel() {
        _state.value = UniversalDownloadState.Cancelled
    }

    fun reset() {
        smoothedSpeedBytesPerSec = 0.0
        lastEmitBytes = 0L
        lastEmitTimeMs = 0L
        _state.value = UniversalDownloadState.Idle
    }
}

data class ComponentDownloadItem(
    val id: String,
    val name: String,
    val totalBytes: Long,
    val downloadedBytes: Long = 0L,
    val state: UniversalDownloadState = UniversalDownloadState.Idle
)

class CompositeDownloadTracker(
    initialItems: List<ComponentDownloadItem>
) {
    private val _items = MutableStateFlow(initialItems)
    val items: StateFlow<List<ComponentDownloadItem>> = _items.asStateFlow()

    val totalBytes: Long get() = _items.value.sumOf { it.totalBytes }
    val totalDownloadedBytes: Long get() = _items.value.sumOf { it.downloadedBytes }
    val compositeProgress: Float get() = if (totalBytes > 0) totalDownloadedBytes.toFloat() / totalBytes else 0f

    fun updateItem(id: String, state: UniversalDownloadState, currentBytes: Long) {
        _items.update { list ->
            list.map { item ->
                if (item.id == id) {
                    item.copy(state = state, downloadedBytes = currentBytes)
                } else item
            }
        }
    }
}
