package com.translive.app.service

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.translive.app.R
import com.translive.app.data.SettingsRepository
import com.translive.app.data.TranslationPolicy
import com.translive.app.data.model.Language
import com.translive.app.engine.FastTranslateEngine
import com.translive.app.engine.LanguageDetectionEngine
import com.translive.app.engine.OcrEngine
import com.translive.app.engine.OcrResult
import com.translive.app.engine.TranslationEngine
import com.translive.app.i18n.LocalizedTextProvider
import com.translive.app.service.capture.ScreenCaptureController
import com.translive.app.ui.MainActivity
import com.translive.app.ui.overlay.ArScanBox
import com.translive.app.ui.overlay.ArTranslateOverlayView
import com.translive.app.ui.overlay.ArTranslatedBox
import com.translive.app.ui.overlay.FloatingBubbleView
import com.translive.app.ui.overlay.LensScanEffectView
import com.translive.app.ui.overlay.MiniOverlayHudView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

/**
 * Interactive floating bubble and silent in-place AR screen translation service.
 * Managed with ScreenCaptureController for MediaProjection lifecycle,
 * FloatingBubbleView for haptic interaction, LensScanEffectView for Google Lens scanning FX,
 * and MiniOverlayHudView for quick target language and engine mode switching.
 */
@AndroidEntryPoint
class ScreenTranslateOverlayService : Service() {

    @Inject lateinit var ocrEngine: OcrEngine
    @Inject lateinit var fastTranslateEngine: FastTranslateEngine
    @Inject lateinit var translationEngine: TranslationEngine
    @Inject lateinit var languageDetectionEngine: LanguageDetectionEngine
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var texts: LocalizedTextProvider

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingBubbleView: FloatingBubbleView? = null
    private var bubbleLayoutParams: WindowManager.LayoutParams? = null
    private var miniOverlayHudView: MiniOverlayHudView? = null
    private var lensScanEffectView: LensScanEffectView? = null
    private var arOverlayView: ArTranslateOverlayView? = null

    private val captureController = ScreenCaptureController()
    private lateinit var prefs: SharedPreferences

    private var lastScreenWidth = 0
    private var lastScreenHeight = 0

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val metrics = resources.displayMetrics
        lastScreenWidth = metrics.widthPixels
        lastScreenHeight = metrics.heightPixels

        captureController.setOnProjectionStoppedListener {
            Log.i(TAG, "ScreenCaptureController reported projection stopped by system")
            mainHandler.post {
                floatingBubbleView?.visibility = View.VISIBLE
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureForeground()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.parcelableIntent(EXTRA_RESULT_DATA)

        if (resultCode == Activity.RESULT_OK && resultData != null) {
            val manager = getSystemService(MediaProjectionManager::class.java)
            try {
                val newProjection = manager?.getMediaProjection(resultCode, resultData)
                if (newProjection != null) {
                    val metrics = resources.displayMetrics
                    captureController.attachProjection(
                        projection = newProjection,
                        width = metrics.widthPixels,
                        height = metrics.heightPixels,
                        densityDpi = metrics.densityDpi
                    )
                    Log.i(TAG, "Attached MediaProjection to ScreenCaptureController")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed initializing MediaProjection", e)
            }
        }

        if (floatingBubbleView == null) {
            showFloatingBubble()
        }

        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = resources.displayMetrics
        val newWidth = metrics.widthPixels
        val newHeight = metrics.heightPixels
        val newDpi = metrics.densityDpi

        Log.i(TAG, "Configuration changed: ${newWidth}x${newHeight} @ ${newDpi}dpi")

        captureController.updateDisplayMetrics(newWidth, newHeight, newDpi)

        bubbleLayoutParams?.let { params ->
            val bubbleSize = floatingBubbleView?.totalViewSizePx ?: (76 * metrics.density).toInt()
            val margin = (16 * metrics.density).toInt()

            val oldWidth = if (lastScreenWidth > 0) lastScreenWidth else newWidth
            val oldHeight = if (lastScreenHeight > 0) lastScreenHeight else newHeight

            val isLeftDocked = (params.x + (bubbleSize / 2)) < (oldWidth / 2)
            val targetX = if (isLeftDocked) margin else (newWidth - bubbleSize - margin)

            val oldUsableSpan = (oldHeight - bubbleSize - (2 * margin)).coerceAtLeast(1)
            val ratioY = ((params.y - margin).toFloat() / oldUsableSpan).coerceIn(0f, 1f)
            val newUsableSpan = (newHeight - bubbleSize - (2 * margin)).coerceAtLeast(1)
            val targetY = (margin + (ratioY * newUsableSpan)).toInt().coerceIn(margin, newHeight - bubbleSize - margin)

            params.x = targetX
            params.y = targetY

            floatingBubbleView?.let { view ->
                runCatching { windowManager?.updateViewLayout(view, params) }
            }
            prefs.edit().putInt(KEY_POS_X, params.x).putInt(KEY_POS_Y, params.y).apply()
        }

        dismissMiniOverlayHud()

        lastScreenWidth = newWidth
        lastScreenHeight = newHeight
    }

    private fun showFloatingBubble() {
        if (floatingBubbleView != null) return

        val bubble = FloatingBubbleView(this)
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val savedX = prefs.getInt(KEY_POS_X, screenWidth - bubble.totalViewSizePx - (16 * density).toInt())
        val savedY = prefs.getInt(KEY_POS_Y, screenHeight / 2 - bubble.totalViewSizePx / 2)

        val params = WindowManager.LayoutParams(
            bubble.totalViewSizePx,
            bubble.totalViewSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceIn(0, screenWidth - bubble.totalViewSizePx)
            y = savedY.coerceIn(0, screenHeight - bubble.totalViewSizePx)
        }
        bubbleLayoutParams = params

        val wm = windowManager ?: return
        bubble.attachToWindowManager(wm, params)
        bubble.listener = object : FloatingBubbleView.BubbleEventListener {
            override fun onBubbleClick() {
                if (miniOverlayHudView != null) {
                    dismissMiniOverlayHud()
                } else {
                    onFloatingBubbleClicked()
                }
            }

            override fun onBubbleLongClick() {
                showMiniOverlayHud(params.x, params.y)
            }

            override fun onPositionChanged(x: Int, y: Int) {}

            override fun onDocked(edge: FloatingBubbleView.DockEdge, y: Int) {
                prefs.edit().putInt(KEY_POS_X, params.x).putInt(KEY_POS_Y, y).apply()
            }
        }

        try {
            wm.addView(bubble, params)
            floatingBubbleView = bubble
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding floating bubble view", e)
        }
    }

    private fun showMiniOverlayHud(buttonX: Int, buttonY: Int) {
        dismissMiniOverlayHud()
        val wm = windowManager ?: return

        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val hudWidth = (280 * density).toInt()

        val hudParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (buttonX < screenWidth / 2) {
                (buttonX + (64 * density).toInt()).coerceIn((12 * density).toInt(), screenWidth - hudWidth - (12 * density).toInt())
            } else {
                (buttonX - hudWidth - (8 * density).toInt()).coerceIn((12 * density).toInt(), screenWidth - hudWidth - (12 * density).toInt())
            }
            y = buttonY.coerceIn((24 * density).toInt(), screenHeight - (320 * density).toInt())
        }

        val hud = MiniOverlayHudView(this)
        val isLlm = settings.translationPolicy != TranslationPolicy.FAST
        hud.setState(settings.cameraTargetLanguage, isLlm)

        hud.listener = object : MiniOverlayHudView.MiniOverlayHudListener {
            override fun onTargetLanguageSelected(language: Language) {
                Log.i(TAG, "Target language selected in HUD: ${language.code}")
                settings.cameraTargetLanguage = language
                Toast.makeText(this@ScreenTranslateOverlayService, "Target: ${language.displayName}", Toast.LENGTH_SHORT).show()
            }

            override fun onEngineModeChanged(isLlm: Boolean) {
                Log.i(TAG, "Engine mode changed in HUD: isLlm=$isLlm")
                settings.translationPolicy = if (isLlm) TranslationPolicy.LLM_ONLY else TranslationPolicy.FAST
                val modeLabel = if (isLlm) "Smart LLM" else "Fast NMT"
                Toast.makeText(this@ScreenTranslateOverlayService, "Engine: $modeLabel", Toast.LENGTH_SHORT).show()
            }

            override fun onScanRequested() {
                dismissMiniOverlayHud()
                onFloatingBubbleClicked()
            }

            override fun onCloseRequested() {
                dismissMiniOverlayHud()
                stopSelf()
            }

            override fun onDismissRequested() {
                dismissMiniOverlayHud()
            }
        }

        try {
            wm.addView(hud, hudParams)
            miniOverlayHudView = hud
            hud.animateEntrance()
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding MiniOverlayHudView", e)
        }
    }

    private fun dismissMiniOverlayHud() {
        miniOverlayHudView?.let { hud ->
            runCatching { windowManager?.removeView(hud) }
        }
        miniOverlayHudView = null
    }

    private fun onFloatingBubbleClicked() {
        if (arOverlayView != null) {
            dismissArOverlay()
            return
        }

        if (!captureController.isAttached) {
            triggerScreenCapturePermission()
            return
        }

        if (captureController.isCapturing) return

        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.CAPTURING)
        showLensScanEffect()

        serviceScope.launch {
            delay(40L)
            val metrics = resources.displayMetrics
            captureController.updateDisplayMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

            val bitmap = captureController.acquireLatestFrame()
            if (bitmap != null) {
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.PROCESSING)
                processAndShowArTranslation(bitmap)
            } else {
                dismissLensScanEffect()
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
                Toast.makeText(this@ScreenTranslateOverlayService, getString(R.string.screen_overlay_capture_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showLensScanEffect() {
        dismissLensScanEffect()
        val wm = windowManager ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val scanView = LensScanEffectView(this)
        scanView.startScan()

        try {
            wm.addView(scanView, params)
            lensScanEffectView = scanView
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding LensScanEffectView", e)
        }
    }

    private fun dismissLensScanEffect() {
        lensScanEffectView?.let {
            it.reset()
            runCatching { windowManager?.removeView(it) }
        }
        lensScanEffectView = null
    }

    private fun processAndShowArTranslation(bitmap: Bitmap) {
        serviceScope.launch {
            try {
                val targetLang = settings.cameraTargetLanguage
                val targetCode = targetLang.code
                val isLlm = settings.translationPolicy != TranslationPolicy.FAST

                val ocrResult: OcrResult = withContext(Dispatchers.Default) {
                    ocrEngine.recognize(bitmap, "auto")
                }

                val validLines = ocrResult.blocks.flatMap { it.lines }
                    .filter { it.text.isNotBlank() && it.boundingBox.width() > 10 }

                if (validLines.isEmpty()) {
                    dismissLensScanEffect()
                    floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
                    Toast.makeText(this@ScreenTranslateOverlayService, getString(R.string.screen_overlay_no_text), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val arBoxes = withContext(Dispatchers.IO) {
                    validLines.map { line ->
                        val text = line.text.trim()
                        val detectedLang = languageDetectionEngine.detect(text)
                        val srcCode = detectedLang.code

                        var translated = text
                        if (srcCode != targetCode) {
                            if (isLlm && translationEngine.isLoaded) {
                                translated = translationEngine.translateSafe(text, detectedLang, targetLang)
                            } else if (fastTranslateEngine.isReadyFor(srcCode, targetCode)) {
                                translated = fastTranslateEngine.translate(text)
                            } else {
                                val activated = fastTranslateEngine.activateDownloadedPair(srcCode, targetCode)
                                if (activated) {
                                    translated = fastTranslateEngine.translate(text)
                                } else if (translationEngine.isLoaded) {
                                    translated = translationEngine.translateSafe(text, detectedLang, targetLang)
                                }
                            }
                        }

                        ArTranslatedBox(
                            rawText = text,
                            translatedText = translated,
                            boundingBox = line.boundingBox,
                            sourceLangCode = srcCode,
                            targetLangCode = targetCode
                        )
                    }
                }

                val scanBoxes = arBoxes.mapIndexed { index, box ->
                    ArScanBox(
                        id = "box_$index",
                        rect = box.boundingBox,
                        primaryText = box.translatedText,
                        secondaryText = box.rawText
                    )
                }

                lensScanEffectView?.revealResults(scanBoxes)
                showArOverlay(arBoxes)
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "AR Translation processing failed", e)
                dismissLensScanEffect()
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
                Toast.makeText(this@ScreenTranslateOverlayService, "Translation error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showArOverlay(boxes: List<ArTranslatedBox>) {
        dismissArOverlay()

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        val overlay = ArTranslateOverlayView(
            context = this,
            boxes = boxes,
            onDismiss = {
                dismissArOverlay()
                dismissLensScanEffect()
            }
        )

        try {
            windowManager?.addView(overlay, params)
            arOverlayView = overlay
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding ArTranslateOverlayView", e)
        }
    }

    private fun dismissArOverlay() {
        arOverlayView?.let { runCatching { windowManager?.removeView(it) } }
        arOverlayView = null
    }

    private fun triggerScreenCapturePermission() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_REQUEST_SCREEN_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun ensureForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screen_overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.screen_overlay_notification_title))
                .setContentText(getString(R.string.screen_overlay_notification_text))
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
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        dismissMiniOverlayHud()
        dismissLensScanEffect()
        dismissArOverlay()
        captureController.release()
        floatingBubbleView?.let { runCatching { windowManager?.removeView(it) } }
        floatingBubbleView = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenOverlayService"
        const val ACTION_REQUEST_SCREEN_CAPTURE = "com.translive.app.action.REQUEST_SCREEN_CAPTURE"
        const val ACTION_REQUEST_LIVE_TRANSLATE = "com.translive.app.action.REQUEST_LIVE_TRANSLATE"
        const val ACTION_SHOW_TRANSLATION = "com.translive.app.action.SHOW_SCREEN_TRANSLATION"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val CHANNEL_ID = "screen_translation_overlay"
        private const val NOTIFICATION_ID = 7102
        private const val PREFS_NAME = "parlex_overlay_prefs"
        private const val KEY_POS_X = "button_pos_x"
        private const val KEY_POS_Y = "button_pos_y"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ScreenTranslateOverlayService::class.java)
            )
        }

        fun startWithProjection(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenTranslateOverlayService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenTranslateOverlayService::class.java))
        }

        @Suppress("DEPRECATION")
        private fun Intent.parcelableIntent(key: String): Intent? =
            getParcelableExtra(key)
    }
}
