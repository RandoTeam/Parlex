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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private val captureMutex = Mutex()
    private val isCapturingFlag = AtomicBoolean(false)

    var currentMetrics: CaptureDisplayMetrics = CaptureDisplayMetrics(0, 0, 0)
        private set

    var state: ScreenCaptureState = ScreenCaptureState.UNATTACHED
        private set

    val isAttached: Boolean
        get() = mediaProjection != null && state != ScreenCaptureState.RELEASED

    val isReady: Boolean
        get() = state == ScreenCaptureState.READY && virtualDisplay != null

    val isCapturing: Boolean
        get() = isCapturingFlag.get()

    private var onProjectionStoppedListener: (() -> Unit)? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.i(TAG, "MediaProjection was terminated by the system")
            cleanupSurfaces()
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

        cleanupSurfaces()
        currentMetrics = CaptureDisplayMetrics(width, height, densityDpi)
        mediaProjection = projection

        val handler = backgroundHandler ?: return false
        return try {
            projection.registerCallback(projectionCallback, handler)
            setupPersistentDisplay(width, height, densityDpi)
            state = ScreenCaptureState.READY
            Log.i(TAG, "Attached MediaProjection and persistent VirtualDisplay: ${width}x${height} @ ${densityDpi}dpi")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed initializing MediaProjection session", e)
            release()
            false
        }
    }

    private fun setupPersistentDisplay(width: Int, height: Int, densityDpi: Int) {
        val handler = backgroundHandler ?: return
        val proj = mediaProjection ?: return

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = proj.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler
        )
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

            oldReader?.setOnImageAvailableListener(null, null)
            oldReader?.close()
        }
        return true
    }

    fun setOnProjectionStoppedListener(listener: (() -> Unit)?) {
        this.onProjectionStoppedListener = listener
    }

    /**
     * Captures a single uncompressed screen frame safely from the persistent VirtualDisplay.
     * Returns null if projection is detached, capturing times out, or buffer conversion fails.
     */
    suspend fun acquireLatestFrame(timeoutMillis: Long = CAPTURE_TIMEOUT_MS): Bitmap? = withContext(Dispatchers.Default) {
        if (!isAttached) {
            Log.w(TAG, "acquireLatestFrame aborted: MediaProjection is not attached")
            return@withContext null
        }

        captureMutex.withLock {
            if (!isCapturingFlag.compareAndSet(false, true)) {
                Log.w(TAG, "acquireLatestFrame aborted: Capture already in progress")
                return@withLock null
            }
            state = ScreenCaptureState.CAPTURING

            val reader = imageReader
            val handler = backgroundHandler
            val width = currentMetrics.width
            val height = currentMetrics.height

            if (reader == null || handler == null || width <= 0 || height <= 0) {
                isCapturingFlag.set(false)
                state = if (isAttached) ScreenCaptureState.READY else ScreenCaptureState.UNATTACHED
                return@withLock null
            }

            try {
                val immediateImage = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (immediateImage != null) {
                    try {
                        return@withLock convertImageToBitmap(immediateImage, width, height)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed converting immediate image buffer", e)
                    } finally {
                        immediateImage.close()
                    }
                }

                withTimeoutOrNull(timeoutMillis) {
                    suspendCancellableCoroutine { continuation ->
                        reader.setOnImageAvailableListener({ source ->
                            val image = runCatching { source.acquireLatestImage() }.getOrNull()
                            if (image != null) {
                                try {
                                    val bitmap = convertImageToBitmap(image, width, height)
                                    if (continuation.isActive) {
                                        reader.setOnImageAvailableListener(null, null)
                                        continuation.resume(bitmap)
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed converting image buffer to Bitmap", e)
                                    if (continuation.isActive) {
                                        reader.setOnImageAvailableListener(null, null)
                                        continuation.resume(null)
                                    }
                                } finally {
                                    image.close()
                                }
                            }
                        }, handler)

                        continuation.invokeOnCancellation {
                            runCatching { reader.setOnImageAvailableListener(null, null) }
                        }
                    }
                }
            } finally {
                runCatching { reader.setOnImageAvailableListener(null, null) }
                isCapturingFlag.set(false)
                state = if (isAttached) ScreenCaptureState.READY else ScreenCaptureState.UNATTACHED
            }
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

    private fun cleanupSurfaces() {
        runCatching { imageReader?.setOnImageAvailableListener(null, null) }
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        runCatching { imageReader?.close() }
        imageReader = null
    }

    @Synchronized
    fun release() {
        cleanupSurfaces()
        val proj = mediaProjection
        mediaProjection = null
        if (proj != null) {
            runCatching { proj.unregisterCallback(projectionCallback) }
            runCatching { proj.stop() }
        }
        state = ScreenCaptureState.RELEASED
        isCapturingFlag.set(false)
        backgroundThread?.quitSafely()
        backgroundThread = null
        backgroundHandler = null
        Log.i(TAG, "ScreenCaptureController completely released")
    }

    companion object {
        private const val TAG = "ScreenCaptureController"
        private const val VIRTUAL_DISPLAY_NAME = "ParlexPersistentDisplay"
        private const val CAPTURE_TIMEOUT_MS = 2500L
    }
}
