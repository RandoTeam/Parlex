package com.translive.app.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.translive.app.R
import com.translive.app.data.SettingsRepository
import com.translive.app.data.model.Language
import com.translive.app.engine.KeyframeMotionDetector
import com.translive.app.engine.LiveTranslationPipeline
import com.translive.app.ui.MainActivity
import com.translive.app.ui.overlay.LiveOverlayRenderer
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

import com.translive.app.service.model.HudAction
import com.translive.app.service.model.HudStatus
import com.translive.app.service.model.HudUiState
import com.translive.app.service.model.ScreenTranslateMode

@AndroidEntryPoint
class LiveScreenTranslateService : Service() {

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var liveTranslationPipeline: LiveTranslationPipeline

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var displayManager: DisplayManager? = null

    private val motionDetector = KeyframeMotionDetector(
        motionThreshold = 0.025f,
        stableDurationMs = 180L,
        gridDimension = 32
    )

    private var overlayRenderer: LiveOverlayRenderer? = null
    private val isProcessingFrame = AtomicBoolean(false)
    private val singleShotPending = AtomicBoolean(false)

    private var hudState = HudUiState(
        mode = ScreenTranslateMode.AUTO_LIVE,
        status = HudStatus.MONITORING,
        sourceLanguage = Language.ENGLISH,
        isSourceAuto = true,
        targetLanguage = Language.RUSSIAN
    )

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) {
                mainHandler.post { recreateVirtualDisplay() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, mainHandler)

        val manager = getSystemService(MediaProjectionManager::class.java)
        projection = manager.getMediaProjection(resultCode, resultData)

        setupOverlay()
        setupVirtualDisplay()

        return START_STICKY
    }

    private fun setupOverlay() {
        overlayRenderer = LiveOverlayRenderer(
            context = this,
            onAction = { action -> handleHudAction(action) }
        )
        overlayRenderer?.show(hudState)
    }

    private fun handleHudAction(action: HudAction) {
        when (action) {
            is HudAction.SetMode -> {
                hudState = hudState.copy(mode = action.mode)
                if (action.mode == ScreenTranslateMode.SINGLE_SHOT) {
                    overlayRenderer?.clearArOverlay()
                }
                overlayRenderer?.updateState(hudState)
            }
            is HudAction.TriggerSingleShot -> {
                singleShotPending.set(true)
            }
            is HudAction.TogglePause -> {
                val nextPaused = !hudState.isPaused
                hudState = hudState.copy(
                    isPaused = nextPaused,
                    status = if (nextPaused) HudStatus.PAUSED else HudStatus.MONITORING
                )
                if (nextPaused) {
                    motionDetector.reset()
                }
                overlayRenderer?.updateState(hudState)
            }
            is HudAction.ToggleInteractiveMode -> {
                hudState = hudState.copy(isInteractiveMode = !hudState.isInteractiveMode)
                overlayRenderer?.updateState(hudState)
            }
            is HudAction.ToggleMiniLangPicker -> {
                hudState = hudState.copy(isMiniLangPickerVisible = !hudState.isMiniLangPickerVisible)
                overlayRenderer?.updateState(hudState)
            }
            is HudAction.SelectSourceLanguage -> {
                hudState = hudState.copy(
                    sourceLanguage = action.language,
                    isSourceAuto = action.isAuto
                )
                overlayRenderer?.updateState(hudState)
            }
            is HudAction.SelectTargetLanguage -> {
                hudState = hudState.copy(targetLanguage = action.language)
                overlayRenderer?.updateState(hudState)
            }
            is HudAction.SwapLanguages -> {
                val oldSrc = hudState.sourceLanguage
                val oldTgt = hudState.targetLanguage
                hudState = hudState.copy(
                    sourceLanguage = oldTgt,
                    targetLanguage = oldSrc,
                    isSourceAuto = false
                )
                overlayRenderer?.updateState(hudState)
            }
            is HudAction.CloseService -> {
                stopSelf()
            }
        }
    }

    private fun setupVirtualDisplay() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, mainHandler)

        reader.setOnImageAvailableListener({ source ->
            if (hudState.isPaused) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
            handleScreenImage(image, width, height)
        }, mainHandler)

        virtualDisplay = projection?.createVirtualDisplay(
            "ParlexLiveStream",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            mainHandler
        )
    }

    private fun recreateVirtualDisplay() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null

        if (projection != null) {
            setupVirtualDisplay()
        }
    }

    private fun handleScreenImage(image: Image, width: Int, height: Int) {
        try {
            if (hudState.mode == ScreenTranslateMode.SINGLE_SHOT) {
                if (singleShotPending.compareAndSet(true, false)) {
                    if (isProcessingFrame.compareAndSet(false, true)) {
                        val bitmap = imageToBitmap(image, width, height)
                        processFrameBitmap(bitmap)
                    }
                }
                return
            }

            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            val detection = motionDetector.processBuffer(
                buffer = buffer,
                width = width,
                height = height,
                rowStride = rowStride,
                pixelStride = pixelStride
            )

            when (detection) {
                is KeyframeMotionDetector.DetectionResult.Stabilizing -> {
                    if (hudState.status != HudStatus.STABILIZING && !isProcessingFrame.get()) {
                        hudState = hudState.copy(status = HudStatus.STABILIZING)
                        overlayRenderer?.updateState(hudState)
                    }
                }
                is KeyframeMotionDetector.DetectionResult.KeyframeTriggered -> {
                    if (isProcessingFrame.compareAndSet(false, true)) {
                        val bitmap = imageToBitmap(image, width, height)
                        processFrameBitmap(bitmap)
                    }
                }
                else -> {
                    if (hudState.status != HudStatus.MONITORING && !isProcessingFrame.get()) {
                        hudState = hudState.copy(status = HudStatus.MONITORING)
                        overlayRenderer?.updateState(hudState)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            image.close()
        }
    }

    private fun processFrameBitmap(bitmap: Bitmap) {
        hudState = hudState.copy(status = HudStatus.TRANSLATING)
        overlayRenderer?.updateState(hudState)

        serviceScope.launch {
            try {
                val frame = liveTranslationPipeline.processKeyframe(
                    bitmap = bitmap,
                    sourceLanguage = hudState.sourceLanguage,
                    targetLanguage = hudState.targetLanguage,
                    showTransliteration = settingsRepository.showTransliteration
                )
                overlayRenderer?.updateFrame(frame)
            } catch (_: Exception) {
            } finally {
                isProcessingFrame.set(false)
                hudState = hudState.copy(
                    status = if (hudState.isPaused) HudStatus.PAUSED else HudStatus.MONITORING
                )
                overlayRenderer?.updateState(hudState)
            }
        }
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, 0, 0, width, height)
        }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Живой перевод экрана",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Фоновый сервис непрерывного перевода экрана"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle("⚡ Живой перевод экрана активен")
        .setContentText("Нажмите для возврата в Parlex")
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            R.mipmap.ic_launcher,
            "Остановить",
            PendingIntent.getService(
                this,
                1,
                Intent(this, LiveScreenTranslateService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    override fun onDestroy() {
        super.onDestroy()
        displayManager?.unregisterDisplayListener(displayListener)
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        overlayRenderer?.dismiss()
        liveTranslationPipeline.reset()
        serviceScope.cancel()
    }

    companion object {
        private const val CHANNEL_ID = "parlex_live_screen_translate"
        private const val NOTIFICATION_ID = 4002

        const val ACTION_START = "com.translive.app.action.START_LIVE_TRANSLATE"
        const val ACTION_STOP = "com.translive.app.action.STOP_LIVE_TRANSLATE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun start(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, LiveScreenTranslateService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, LiveScreenTranslateService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
