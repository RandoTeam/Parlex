package com.translive.app.service.capture

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

enum class ScreenCaptureState {
    UNATTACHED,
    READY,
    CAPTURING,
    RELEASED
}

data class CaptureDisplayMetrics(
    val width: Int,
    val height: Int,
    val densityDpi: Int
)

/**
 * Controller managing MediaProjection lifecycle, persistent VirtualDisplay streaming,
 * and low-latency frame extraction with stride padding correction.
 */
class ScreenCaptureController {

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    var currentMetrics: CaptureDisplayMetrics = CaptureDisplayMetrics(0, 0, 0)
        private set

    private val capturing = AtomicBoolean(false)
    var state: ScreenCaptureState = ScreenCaptureState.UNATTACHED
        private set

    val isAttached: Boolean
        get() = mediaProjection != null

    val isReady: Boolean
        get() = state == ScreenCaptureState.READY

    val isCapturing: Boolean
        get() = capturing.get()

    private var onProjectionStoppedListener: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection was terminated by the system")
            cleanupInternal(releaseProjection = false)
            mediaProjection = null
            state = ScreenCaptureState.RELEASED
            onProjectionStoppedListener?.invoke()
        }
    }

    init {
        val thread = HandlerThread("ScreenCaptureBackground", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
    }

    /**
     * Attaches a newly granted MediaProjection session.
     * Replaces and releases any previously held projection.
     */
    @Synchronized
    fun attachProjection(
        projection: MediaProjection,
        width: Int,
        height: Int,
        densityDpi: Int
    ): Boolean {
        if (width <= 0 || height <= 0 || densityDpi <= 0) {
            Log.e(TAG, "Invalid dimensions: ${width}x${height} @ ${densityDpi}dpi")
            return false
        }

        releaseResources(releaseProjection = true)

        currentMetrics = CaptureDisplayMetrics(width, height, densityDpi)
        mediaProjection = projection

        val handler = backgroundHandler ?: return false
        return try {
            projection.registerCallback(projectionCallback, handler)
            state = ScreenCaptureState.READY
            Log.i(TAG, "Attached MediaProjection: ${width}x${height} @ ${densityDpi}dpi")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register MediaProjection callback", e)
            release()
            false
        }
    }

    /**
     * Updates display dimensions and density upon device orientation or resolution change.
     */
    @Synchronized
    fun updateDisplayMetrics(width: Int, height: Int, densityDpi: Int): Boolean {
        if (width <= 0 || height <= 0 || densityDpi <= 0) return false
        if (currentMetrics.width == width && currentMetrics.height == height && currentMetrics.densityDpi == densityDpi) {
            return false
        }

        currentMetrics = CaptureDisplayMetrics(width, height, densityDpi)
        Log.i(TAG, "Updated display metrics: ${width}x${height} @ ${densityDpi}dpi")

        val vd = virtualDisplay
        val handler = backgroundHandler
        if (vd != null && handler != null) {
            val oldReader = imageReader
            val newReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            imageReader = newReader

            vd.resize(width, height, densityDpi)
            vd.surface = newReader.surface

            oldReader?.close()
        }
        return true
    }

    fun setOnProjectionStoppedListener(listener: (() -> Unit)?) {
        this.onProjectionStoppedListener = listener
    }

    /**
     * Captures a single uncompressed screen frame safely within a coroutine context.
     * Returns null if projection is detached, capturing times out, or buffer conversion fails.
     */
    suspend fun acquireLatestFrame(timeoutMillis: Long = CAPTURE_TIMEOUT_MS): Bitmap? = withContext(Dispatchers.Default) {
        val projection = mediaProjection ?: run {
            Log.w(TAG, "acquireLatestFrame aborted: MediaProjection is not attached")
            return@withContext null
        }

        if (!capturing.compareAndSet(false, true)) {
            Log.w(TAG, "acquireLatestFrame aborted: Capture already in progress")
            return@withContext null
        }
        state = ScreenCaptureState.CAPTURING

        val width = currentMetrics.width
        val height = currentMetrics.height
        val density = currentMetrics.densityDpi
        val handler = backgroundHandler

        if (width <= 0 || height <= 0 || density <= 0 || handler == null) {
            capturing.set(false)
            state = if (mediaProjection != null) ScreenCaptureState.READY else ScreenCaptureState.UNATTACHED
            return@withContext null
        }

        var localReader: ImageReader? = null
        var localDisplay: VirtualDisplay? = null

        try {
            withTimeoutOrNull(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                    localReader = reader

                    reader.setOnImageAvailableListener({ source ->
                        val image = runCatching { source.acquireLatestImage() }.getOrNull()
                        if (image != null) {
                            try {
                                val bitmap = convertImageToBitmap(image, width, height)
                                if (continuation.isActive) {
                                    continuation.resume(bitmap)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed converting image buffer to Bitmap", e)
                                if (continuation.isActive) {
                                    continuation.resume(null)
                                }
                            } finally {
                                image.close()
                            }
                        }
                    }, handler)

                    try {
                        localDisplay = projection.createVirtualDisplay(
                            VIRTUAL_DISPLAY_NAME,
                            width,
                            height,
                            density,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                            reader.surface,
                            null,
                            handler
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create VirtualDisplay", e)
                        if (continuation.isActive) {
                            continuation.resume(null)
                        }
                    }

                    continuation.invokeOnCancellation {
                        runCatching { reader.setOnImageAvailableListener(null, null) }
                        runCatching { localDisplay?.release() }
                        runCatching { reader.close() }
                    }
                }
            }
        } finally {
            runCatching { localReader?.setOnImageAvailableListener(null, null) }
            runCatching { localDisplay?.release() }
            runCatching { localReader?.close() }
            capturing.set(false)
            state = if (mediaProjection != null) ScreenCaptureState.READY else ScreenCaptureState.UNATTACHED
        }
    }

    private fun convertImageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val layout = ScreenCaptureUtils.computeStrideLayout(width, height, rowStride, pixelStride)

        buffer.rewind()
        val paddedBitmap = Bitmap.createBitmap(
            layout.paddedWidth,
            layout.paddedHeight,
            Bitmap.Config.ARGB_8888
        )
        paddedBitmap.copyPixelsFromBuffer(buffer)

        return if (!layout.requiresCropping) {
            paddedBitmap
        } else {
            val croppedBitmap = Bitmap.createBitmap(
                paddedBitmap,
                layout.cropX,
                layout.cropY,
                layout.cropWidth,
                layout.cropHeight
            )
            paddedBitmap.recycle()
            croppedBitmap
        }
    }

    private fun cleanupVirtualDisplayAndReader() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
    }

    private fun cleanupInternal(releaseProjection: Boolean) {
        cleanupVirtualDisplayAndReader()
        if (releaseProjection) {
            val proj = mediaProjection
            mediaProjection = null
            if (proj != null) {
                runCatching { proj.unregisterCallback(projectionCallback) }
                runCatching { proj.stop() }
            }
        }
    }

    @Synchronized
    fun release() {
        releaseResources(releaseProjection = true)
        state = ScreenCaptureState.RELEASED
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        Log.i(TAG, "ScreenCaptureController released")
    }

    private fun releaseResources(releaseProjection: Boolean) {
        cleanupInternal(releaseProjection)
        capturing.set(false)
    }

    companion object {
        private const val TAG = "ScreenCaptureController"
        private const val VIRTUAL_DISPLAY_NAME = "ParlexCaptureDisplay"
        private const val CAPTURE_TIMEOUT_MS = 3000L
    }
}
