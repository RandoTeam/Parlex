package com.translive.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import com.translive.app.R
import com.translive.app.ui.MainActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Captures exactly one user-approved screen frame and returns it to camera translation. */
class ScreenCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val completed = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var fromOverlay: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.parcelableIntent(EXTRA_RESULT_DATA) ?: return stopWithFailure()
        fromOverlay = intent?.getBooleanExtra(EXTRA_FROM_OVERLAY, false) ?: false
        if (resultCode != Activity.RESULT_OK) return stopWithFailure()

        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, resultData)
        startCapture()
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = finishCapture()
        }, mainHandler)
        reader.setOnImageAvailableListener({ source ->
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val bitmap = image.toBitmap(width, height)
                complete(bitmap)
            } catch (_: Exception) {
                finishCapture()
            } finally {
                image.close()
            }
        }, mainHandler)
        virtualDisplay = projection?.createVirtualDisplay(
            "ParlexScreenCapture",
            width,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )
        mainHandler.postDelayed({ finishCapture() }, CAPTURE_TIMEOUT_MS)
    }

    private fun complete(bitmap: Bitmap) {
        if (!completed.compareAndSet(false, true)) return
        try {
            val directory = File(cacheDir, "screen_captures").apply { mkdirs() }
            val file = File(directory, "screen-${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { stream ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            if (fromOverlay) {
                val overlayIntent = Intent(this, ScreenTranslateOverlayService::class.java)
                    .setAction(ScreenTranslateOverlayService.ACTION_SHOW_TRANSLATION)
                    .setData(uri)
                androidx.core.content.ContextCompat.startForegroundService(this, overlayIntent)
            } else {
                val openApp = Intent(this, MainActivity::class.java)
                    .setAction(ACTION_CAPTURE_COMPLETE)
                    .setData(uri)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(openApp)
            }
        } catch (_: Exception) {
            // The foreground notification disappears; the app remains usable if capture fails.
        } finally {
            finishCapture()
        }
    }

    private fun Image.toBitmap(width: Int, height: Int): Bitmap {
        val plane = planes.first()
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * width
        val padded = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createBitmap(padded, 0, 0, width, height)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(getString(R.string.screen_capture_notification_title))
        .setContentText(getString(R.string.screen_capture_notification_text))
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screen_capture_notification_title),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun finishCapture() {
        if (!completed.compareAndSet(false, true) && virtualDisplay == null && imageReader == null && projection == null) return
        mainHandler.removeCallbacksAndMessages(null)
        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null
        virtualDisplay?.release()
        virtualDisplay = null
        val activeProjection = projection
        projection = null
        activeProjection?.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopWithFailure(): Int {
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_CAPTURE_COMPLETE = "com.translive.app.action.SCREEN_CAPTURE_COMPLETE"
        const val EXTRA_FROM_OVERLAY = "from_overlay"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_capture"
        private const val NOTIFICATION_ID = 7101
        private const val CAPTURE_TIMEOUT_MS = 5_000L

        fun newCaptureIntent(context: Context, resultCode: Int, resultData: Intent, fromOverlay: Boolean = false) =
            Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_FROM_OVERLAY, fromOverlay)

        @Suppress("DEPRECATION")
        private fun Intent.parcelableIntent(key: String): Intent? =
            getParcelableExtra(key)
    }
}
