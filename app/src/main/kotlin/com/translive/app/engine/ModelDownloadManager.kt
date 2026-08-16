package com.translive.app.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.translive.app.data.model.ModelVariant
import com.translive.app.data.model.SttModelInfo
import com.translive.app.service.DownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
    data class Paused(val bytesDownloaded: Long, val totalBytes: Long) : DownloadState() {
        val progress: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    }
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
        private const val PENDING_IDS_KEY = "pending_model_ids"
        private const val PENDING_TASKS_KEY = "pending_model_tasks"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val downloadPrefs: SharedPreferences =
        context.getSharedPreferences("parlex_downloads", Context.MODE_PRIVATE)

    /** Persistent scope — survives ViewModel lifecycle. */
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Active downloads: variantId → state. Observable by ViewModel and Service. */
    private val _activeDownloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val activeDownloads: StateFlow<Map<String, DownloadState>> = _activeDownloads.asStateFlow()

    /** Per-download completion callbacks. */
    private val completionCallbacks = mutableMapOf<String, suspend (DownloadState) -> Unit>()

    @Volatile private var cancelledIds = mutableSetOf<String>()
    @Volatile private var pausedIds = mutableSetOf<String>()

    /** Active download jobs. */
    private val activeJobs = mutableMapOf<String, Job>()

    init {
        restorePausedDownloads()
    }

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
        pausedIds.remove(variant.id)
        persistPending(variant.id, true, destFile)

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

            verifyDownload(variant, tempFile)
            if (!tempFile.renameTo(destFile)) {
                throw IllegalStateException("Could not finalize downloaded model")
            }
            emit(DownloadState.Completed)
        } catch (e: Exception) {
            if (variant.id in cancelledIds) emit(DownloadState.Cancelled)
            else emit(DownloadState.Failed(e.message ?: "Unknown error"))
        }
    }.flowOn(Dispatchers.IO)

    fun cancelDownload(variantId: String) {
        cancelledIds.add(variantId)
        pausedIds.remove(variantId)
        activeJobs[variantId]?.cancel()
        activeJobs.remove(variantId)
        _activeDownloads.update { it - variantId }
        pendingDestination(variantId)?.let { destination ->
            File(destination.parent, "${destination.name}.tmp").delete()
        }
        persistPending(variantId, false)
    }

    /** Stop network work but retain bytes and a durable resumable task. */
    fun pauseDownload(variantId: String) {
        if (activeJobs[variantId]?.isActive != true) return
        pausedIds.add(variantId)
        activeJobs[variantId]?.cancel()
    }

    fun cancelAll() {
        activeJobs.keys.toList().forEach { cancelDownload(it) }
    }

    private suspend fun executeDownload(variant: ModelVariant, destFile: File) {
        cancelledIds.remove(variant.id)
        pausedIds.remove(variant.id)

        try {
            destFile.parentFile?.mkdirs()
            val tempFile = File(destFile.parent, "${destFile.name}.tmp")
            var existingBytes = tempFile.takeIf { it.exists() }?.length() ?: 0L
            val requestBuilder = Request.Builder().url(variant.downloadUrl)
            if (existingBytes > 0L) requestBuilder.header("Range", "bytes=$existingBytes-")
            _activeDownloads.update {
                it + (variant.id to DownloadState.Downloading(existingBytes, variant.sizeBytes, 0))
            }

            val request = requestBuilder.build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                finishDownload(variant.id, DownloadState.Failed("HTTP ${response.code}: ${response.message}"))
                return
            }

            val body = response.body ?: run {
                finishDownload(variant.id, DownloadState.Failed("Empty response body"))
                return
            }

            // A 200 response to a range request means the host ignored Range.
            // Restart instead of appending duplicated bytes.
            val append = existingBytes > 0L && response.code == 206
            if (!append && existingBytes > 0L) {
                tempFile.delete()
                existingBytes = 0L
            }
            val totalBytes = parseTotalBytes(response.header("Content-Range"), body.contentLength(), existingBytes, variant.sizeBytes)
            var bytesDownloaded = existingBytes
            var lastEmitTime = System.currentTimeMillis()
            var lastEmitBytes = 0L

            FileOutputStream(tempFile, append).buffered().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (variant.id in cancelledIds || !currentCoroutineContext().isActive) {
                            throw CancellationException("Download paused or cancelled")
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

            verifyDownload(variant, tempFile)
            if (!tempFile.renameTo(destFile)) {
                throw IllegalStateException("Could not finalize downloaded model")
            }
            finishDownload(variant.id, DownloadState.Completed)
            Log.i(TAG, "Download completed: ${variant.id}")

        } catch (e: CancellationException) {
            val temp = File(destFile.parent, "${destFile.name}.tmp")
            if (variant.id in pausedIds) {
                val paused = DownloadState.Paused(temp.length(), variant.sizeBytes)
                persistPending(variant.id, true, destFile)
                finishDownload(variant.id, paused)
            } else {
                temp.delete()
                finishDownload(variant.id, DownloadState.Cancelled)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed ${variant.id}: ${e.message}", e)
            finishDownload(variant.id,
                if (variant.id in cancelledIds) DownloadState.Cancelled
                else DownloadState.Failed(e.message ?: "Unknown error"))
        }
    }

    private suspend fun finishDownload(variantId: String, state: DownloadState) {
        _activeDownloads.update { current ->
            if (state is DownloadState.Paused) current + (variantId to state) else current - variantId
        }
        activeJobs.remove(variantId)
        if (state is DownloadState.Completed || state is DownloadState.Cancelled) persistPending(variantId, false)
        completionCallbacks.remove(variantId)?.invoke(state)
        if (_activeDownloads.value.values.none { it is DownloadState.Downloading }) {
            try { DownloadService.stop(context) } catch (_: Exception) {}
        }
    }

    fun getDownloadState(variantId: String): DownloadState =
        _activeDownloads.value[variantId] ?: DownloadState.Idle

    fun isAnyDownloadActive(): Boolean = _activeDownloads.value.values.any { it is DownloadState.Downloading }

    private fun restorePausedDownloads() {
        val savedDestinations = downloadPrefs.getStringSet(PENDING_TASKS_KEY, emptySet()).orEmpty()
            .mapNotNull { entry -> entry.substringBefore('|').takeIf { '|' in entry }?.let { it to entry.substringAfter('|') } }
            .toMap()
        val pendingIds = if (savedDestinations.isNotEmpty()) savedDestinations.keys else
            downloadPrefs.getStringSet(PENDING_IDS_KEY, emptySet()).orEmpty()
        val restored = pendingIds
            .mapNotNull { id ->
                val variant = knownVariant(id) ?: return@mapNotNull null
                val destination = savedDestinations[id]?.let(::safeDestination)
                    ?: File(context.filesDir, "models/${variant.filename}")
                val temp = File(destination.parent, "${destination.name}.tmp")
                if (!temp.exists() || temp.length() <= 0L) {
                    persistPending(id, false)
                    return@mapNotNull null
                }
                id to DownloadState.Paused(temp.length(), variant.sizeBytes)
            }.toMap()
        _activeDownloads.value = restored
    }

    private fun persistPending(id: String, present: Boolean, destination: File? = null) {
        val next = downloadPrefs.getStringSet(PENDING_IDS_KEY, emptySet()).orEmpty().toMutableSet()
        if (present) next += id else next -= id
        val taskEntries = downloadPrefs.getStringSet(PENDING_TASKS_KEY, emptySet()).orEmpty()
            .filterNot { it.substringBefore('|') == id }.toMutableSet()
        if (present && destination != null) taskEntries += "$id|${destination.absolutePath}"
        downloadPrefs.edit().putStringSet(PENDING_IDS_KEY, next).putStringSet(PENDING_TASKS_KEY, taskEntries).apply()
    }

    private fun knownVariant(id: String): ModelVariant? =
        ModelVariant.findById(id) ?: SttModelInfo.findDownloadVariant(id)

    private fun pendingDestination(id: String): File? =
        downloadPrefs.getStringSet(PENDING_TASKS_KEY, emptySet()).orEmpty()
            .firstOrNull { it.substringBefore('|') == id }
            ?.substringAfter('|')
            ?.let(::safeDestination)

    private fun safeDestination(path: String): File? = try {
        val root = context.filesDir.canonicalFile
        val file = File(path).canonicalFile
        file.takeIf { it.path.startsWith(root.path + File.separator) }
    } catch (_: Exception) { null }

    private fun parseTotalBytes(contentRange: String?, contentLength: Long, existing: Long, fallback: Long): Long {
        val fromRange = contentRange?.substringAfterLast('/')?.toLongOrNull()
        return fromRange ?: if (contentLength > 0) existing + contentLength else fallback
    }

    /**
     * Never activate a catalog model until the downloaded bytes match the
     * publisher's SHA-256. This is particularly important for multi-gigabyte
     * LiteRT-LM assets, for which a partial/corrupted file may otherwise load
     * with confusing native errors.
     */
    private fun verifyDownload(variant: ModelVariant, file: File) {
        val expected = variant.sha256 ?: return
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { byte -> "%02x".format(byte) }
        if (!actual.equals(expected, ignoreCase = true)) {
            file.delete()
            throw IllegalStateException("SHA-256 verification failed for ${variant.filename}")
        }
    }
}
