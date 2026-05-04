package com.translive.app.engine

import com.translive.app.data.model.ModelVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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

@Singleton
class ModelDownloadManager @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var cancelledIds = mutableSetOf<String>()

    fun cancelDownload(variantId: String) {
        cancelledIds.add(variantId)
    }

    fun downloadModel(variant: ModelVariant, destFile: File): Flow<DownloadState> = flow {
        cancelledIds.remove(variant.id)
        emit(DownloadState.Downloading(0, variant.sizeBytes, 0))

        try {
            // Ensure parent dir exists
            destFile.parentFile?.mkdirs()

            // Create temp file to avoid partial downloads being used
            val tempFile = File(destFile.parent, "${destFile.name}.tmp")

            val request = Request.Builder()
                .url(variant.downloadUrl)
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                emit(DownloadState.Failed("HTTP ${response.code}: ${response.message}"))
                return@flow
            }

            val body = response.body ?: run {
                emit(DownloadState.Failed("Empty response body"))
                return@flow
            }

            val totalBytes = body.contentLength().let {
                if (it > 0) it else variant.sizeBytes
            }

            var bytesDownloaded = 0L
            var lastEmitTime = System.currentTimeMillis()
            var lastEmitBytes = 0L

            tempFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        // Check cancellation
                        if (variant.id in cancelledIds) {
                            tempFile.delete()
                            emit(DownloadState.Cancelled)
                            return@flow
                        }

                        output.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead

                        // Emit progress every 200ms
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

            // Move temp to final destination
            tempFile.renameTo(destFile)
            emit(DownloadState.Completed)

        } catch (e: Exception) {
            if (variant.id in cancelledIds) {
                emit(DownloadState.Cancelled)
            } else {
                emit(DownloadState.Failed(e.message ?: "Unknown error"))
            }
        }
    }.flowOn(Dispatchers.IO)
}
