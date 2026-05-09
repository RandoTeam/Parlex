package com.translive.app.engine

import android.content.Context
import android.util.Log
import com.translive.app.data.model.ModelVariant
import com.translive.app.service.DownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Downloading(
        val bytesDownloaded: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long
    ) : DownloadState() {
        val progress: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
        val progressPercent: Int get() = (progress * 100).toInt()
        val etaSeconds: Long get() = if (speedBytesPerSec > 0) (totalBytes - bytesDownloaded) / speedBytesPerSec else -1
    }
    data object Completed : DownloadState()
    data class Failed(val error: String) : DownloadState()
    data object Cancelled : DownloadState()
}

/**
 * Application-scoped download manager. Owns its own CoroutineScope so downloads
 * survive ViewModel clears (screen navigation) and continue in background via
 * DownloadService foreground notification.
 */
@Singleton
class ModelDownloadManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelDownloadManager"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /** Persistent scope — survives ViewModel lifecycle. */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Active downloads: variantId → state. Observable by ViewModel and Service. */
    private val _activeDownloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadState>> = _activeDownloads.asStateFlow()

    /** Per-download completion callbacks. */
    private val completionCallbacks = mutableMapOf<String, suspend (DownloadState) -> Unit>()

    @Volatile
    private var cancelledIds = mutableSetOf<String>()

    /** Active download jobs. */
    private val activeJobs = mutableMapOf<String, Job>()

    /**
     * Start a download. Runs in managerScope so it survives ViewModel clears.
     * [onFinished] is called with the terminal state (Completed/Failed/Cancelled).
     */
    fun startDownload(
        variant: ModelVariant,
        destFile: File,
        onFinished: (suspend (DownloadState) -> Unit)? = null
    ) {
        if (activeJobs[variant.id]?.isActive == true) {
            Log.w(TAG, "Download already active: ${variant.id}")
            return
        }

        onFinished?.let { completionCallbacks[variant.id] = it }

        try { DownloadService.start(context) } catch (e: Exception) {
            Log.w(TAG, "Could not start DownloadService: ${e.message}")
        }

        activeJobs[variant.id] = managerScope.launch {
            executeDownload(variant, destFile)
        }
    }

    /**
     * Legacy flow-based API — still works for inline collection.
     */
    fun downloadModel(variant: ModelVariant, destFile: File): Flow<DownloadState> = flow {
        cancelledIds.remove(variant.id)
        emit(DownloadState.Downloading(0, variant.sizeBytes, 0))

        try {
            destFile.parentFile?.mkdirs()
            val tempFile = File(destFile.parent, "${destFile.name}.tmp")

            val request = Request.Builder().url(variant.downloadUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(DownloadState.Failed("HTTP ${response.code}: ${response.message}"))
                return@flow
            }

            val body = response.body ?: run {
                emit(DownloadState.Failed("Empty response body"))
                return@flow
            }

            val totalBytes = body.contentLength().let { if (it > 0) it else variant.sizeBytes }
            var bytesDownloaded = 0L
            var lastEmitTime = System.currentTimeMillis()
            var lastEmitBytes = 0L

            tempFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (variant.id in cancelledIds) {
                            tempFile.delete()
                            emit(DownloadState.Cancelled)
                            return@flow
                        }
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastEmitTime
                        if (elapsed >= 200) {
                            val speed = ((bytesDownloaded - lastEmitBytes) * 1000) / elapsed
                            emit(DownloadState.Downloading(bytesDownloaded, totalBytes, speed))
                            lastEmitTime = now
                            lastEmitBytes = bytesDownloaded
                        }
                    }
                }
            }

            tempFile.renameTo(destFile)
            emit(DownloadState.Completed)
        } catch (e: Exception) {
            if (variant.id in cancelledIds) emit(DownloadState.Cancelled)
            else emit(DownloadState.Failed(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    fun cancelDownload(variantId: String) {
        cancelledIds.add(variantId)
        activeJobs[variantId]?.cancel()
        activeJobs.remove(variantId)
        _activeDownloads.update { it - variantId }
    }

    fun cancelAll() {
        activeJobs.keys.toList().forEach { cancelDownload(it) }
    }

    private suspend fun executeDownload(variant: ModelVariant, destFile: File) {
        cancelledIds.remove(variant.id)
        _activeDownloads.update { it + (variant.id to DownloadState.Downloading(0, variant.sizeBytes, 0)) }

        try {
            destFile.parentFile?.mkdirs()
            val tempFile = File(destFile.parent, "${destFile.name}.tmp")

            val request = Request.Builder().url(variant.downloadUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                finishDownload(variant.id, DownloadState.Failed("HTTP ${response.code}: ${response.message}"))
                return
            }

            val body = response.body ?: run {
                finishDownload(variant.id, DownloadState.Failed("Empty response body"))
                return
            }

            val totalBytes = body.contentLength().let { if (it > 0) it else variant.sizeBytes }
            var bytesDownloaded = 0L
            var lastEmitTime = System.currentTimeMillis()
            var lastEmitBytes = 0L

            tempFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (variant.id in cancelledIds || !currentCoroutineContext().isActive) {
                            tempFile.delete()
                            finishDownload(variant.id, DownloadState.Cancelled)
                            return
                        }
                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        val now = System.currentTimeMillis()
                        val elapsed = now - lastEmitTime
                        if (elapsed >= 300) {
                            val speed = ((bytesDownloaded - lastEmitBytes) * 1000) / elapsed
                            _activeDownloads.update {
                                it + (variant.id to DownloadState.Downloading(bytesDownloaded, totalBytes, speed))
                            }
                            lastEmitTime = now
                            lastEmitBytes = bytesDownloaded
                        }
                    }
                }
            }

            tempFile.renameTo(destFile)
            finishDownload(variant.id, DownloadState.Completed)
            Log.i(TAG, "Download completed: ${variant.id}")

        } catch (e: CancellationException) {
            finishDownload(variant.id, DownloadState.Cancelled)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed ${variant.id}: ${e.message}", e)
            finishDownload(variant.id,
                if (variant.id in cancelledIds) DownloadState.Cancelled
                else DownloadState.Failed(e.message ?: "Unknown error"))
        }
    }

    private suspend fun finishDownload(variantId: String, state: DownloadState) {
        _activeDownloads.update { it - variantId }
        activeJobs.remove(variantId)
        completionCallbacks.remove(variantId)?.invoke(state)
        if (_activeDownloads.value.isEmpty()) {
            try { DownloadService.stop(context) } catch (_: Exception) {}
        }
    }

    fun getDownloadState(variantId: String): DownloadState =
        _activeDownloads.value[variantId] ?: DownloadState.Idle

    fun isAnyDownloadActive(): Boolean = _activeDownloads.value.isNotEmpty()
}
