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
import android.graphics.Rect
import android.media.projection.MediaProjectionManager
import android.os.Build
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
import com.translive.app.data.ModelRepository
import com.translive.app.data.TranslationPolicy
import com.translive.app.data.model.Language
import com.translive.app.data.model.ModelRuntime
import com.translive.app.engine.FastTranslateEngine
import com.translive.app.engine.LanguageDetectionEngine
import com.translive.app.engine.LiteRtTranslationEngine
import com.translive.app.engine.OcrEngine
import com.translive.app.engine.OcrResult
import com.translive.app.engine.TranslationEngine
import com.translive.app.engine.clustering.ArBoundingBoxClusterer
import com.translive.app.i18n.LocalizedTextProvider
import com.translive.app.service.accessibility.ScreenAccessibilityService
import com.translive.app.service.capture.ScreenCaptureController
import com.translive.app.ui.MainActivity
import com.translive.app.ui.overlay.ArScanBox
import com.translive.app.ui.overlay.ArTranslateOverlayView
import com.translive.app.ui.overlay.ArTranslatedBox
import com.translive.app.ui.overlay.CandidateBlock
import com.translive.app.service.overlay.DragDismissAction
import com.translive.app.service.overlay.DragToDismissCalculator
import com.translive.app.ui.overlay.DismissTrashView
import com.translive.app.ui.overlay.FloatingBubbleView
import com.translive.app.ui.overlay.LensScanEffectView
import com.translive.app.ui.overlay.MiniOverlayHudView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

@AndroidEntryPoint
class ScreenTranslateOverlayService : Service() {

    @Inject lateinit var ocrEngine: OcrEngine
    @Inject lateinit var fastTranslateEngine: FastTranslateEngine
    @Inject lateinit var translationEngine: TranslationEngine
    @Inject lateinit var liteRtTranslationEngine: LiteRtTranslationEngine
    @Inject lateinit var modelRepository: ModelRepository
    @Inject lateinit var languageDetectionEngine: LanguageDetectionEngine
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var texts: LocalizedTextProvider
    @Inject lateinit var screenTranslationExporter: com.translive.app.engine.export.ScreenTranslationExporter

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isOneShotSession: Boolean = false
    private var windowManager: WindowManager? = null
    private var floatingBubbleView: FloatingBubbleView? = null
    private var bubbleLayoutParams: WindowManager.LayoutParams? = null
    private var miniOverlayHudView: MiniOverlayHudView? = null
    private var lensScanEffectView: LensScanEffectView? = null
    private var arOverlayView: ArTranslateOverlayView? = null
    private var dismissTrashView: DismissTrashView? = null
    private var dragDismissCalculator: DragToDismissCalculator? = null

    private var lastCapturedBitmap: Bitmap? = null
    private var lastArBoxes: List<ArTranslatedBox> = emptyList()
    private var lastCandidates: List<CandidateBlock> = emptyList()
    private var overlayShownAtMs = 0L

    private val captureController = ScreenCaptureController()
    private val translationMutex = Mutex()
    private var activeTranslationJob: Job? = null
    private var retranslateJob: Job? = null
    private var activeSourceCode: String = "auto"
    private var activeTargetCode: String = "ru"
    private lateinit var prefs: SharedPreferences

    private var lastScreenWidth = 0
    private var lastScreenHeight = 0

    override fun onCreate() {
        super.onCreate()
        _isServiceRunning.value = true
        instanceRef = java.lang.ref.WeakReference(this)
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
            Log.w(TAG, "Overlay permission not granted, stopping service")
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
                    floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize MediaProjection in service", e)
            }
        }

        val isOneShot = intent?.getBooleanExtra(EXTRA_ONE_SHOT_MODE, false) ?: false
        if (isOneShot) {
            isOneShotSession = true
        }

        if (intent?.action == ACTION_SHOW_TRANSLATION) {
            val uri = intent.data
            if (uri != null && acquireTranslationLock()) {
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.SCANNING)
                setBubbleVisible(false)
                activeTranslationJob = serviceScope.launch {
                    try {
                        val bitmap = withContext(Dispatchers.IO) {
                            runCatching {
                                contentResolver.openInputStream(uri)?.use { input ->
                                    android.graphics.BitmapFactory.decodeStream(input)
                                }
                            }.getOrNull()
                        }
                        setBubbleVisible(true)
                        if (bitmap != null) {
                            showLensScanEffect()
                            floatingBubbleView?.setState(FloatingBubbleView.BubbleState.TRANSLATING)
                            processAndShowArTranslation(bitmap = bitmap)
                        } else {
                            floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
                        }
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Cancelled by user.
                    } catch (e: Exception) {
                        Log.e(TAG, "Screenshot URI translation failed", e)
                        dismissLensScanEffect()
                        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
                    } finally {
                        setBubbleVisible(true)
                        releaseTranslationLock()
                        activeTranslationJob = null
                    }
                }
            }
        }

        if (intent?.action == ACTION_TRANSLATE_NODES) {            val nodes: List<com.translive.app.service.accessibility.AccessibilityTextNode>?
            val bmp: Bitmap?
            synchronized(pendingLock) {
                nodes = pendingDirectNodes
                bmp = pendingDirectBitmap
                pendingDirectNodes = null
                pendingDirectBitmap = null
            }
            if (nodes != null && acquireTranslationLock()) {
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.TRANSLATING)
                showLensScanEffect()
                activeTranslationJob = serviceScope.launch {
                    try {
                        processAndShowArTranslation(bitmap = bmp, directNodes = nodes)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Cancelled by user
                    } catch (e: Exception) {
                        Log.e(TAG, "Direct nodes translate error", e)
                        dismissLensScanEffect()
                        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
                    } finally {
                        releaseTranslationLock()
                        activeTranslationJob = null
                    }
                }
            }
        }

        if (intent?.action == ACTION_TRANSLATE_BITMAP) {
            val bmp = pendingDirectBitmap
            pendingDirectBitmap = null
            if (bmp != null && acquireTranslationLock()) {
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.TRANSLATING)
                showLensScanEffect()
                activeTranslationJob = serviceScope.launch {
                    try {
                        processAndShowArTranslation(bitmap = bmp, directNodes = null)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Cancelled by user
                    } catch (e: Exception) {
                        Log.e(TAG, "Direct translate error", e)
                        dismissLensScanEffect()
                        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
                    } finally {
                        releaseTranslationLock()
                        activeTranslationJob = null
                    }
                }
            }
        }

        if (!isOneShotSession && floatingBubbleView == null) {
            showFloatingBubble()
        }

        return if (isOneShotSession) START_NOT_STICKY else START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = resources.displayMetrics
        val newWidth = metrics.widthPixels
        val newHeight = metrics.heightPixels

        captureController.updateDisplayMetrics(newWidth, newHeight, metrics.densityDpi)

        bubbleLayoutParams?.let { params ->
            val density = metrics.density
            val bubbleSize = (76 * density).toInt()
            val margin = (12 * density).toInt()

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
        if (arOverlayView != null || activeTranslationJob?.isActive == true) {
            // Frozen screenshots and in-flight geometry are invalid after a
            // rotation/display-size change.
            retranslateJob?.cancel()
            cancelActiveTranslation()
            dismissArOverlay()
            if (isOneShotSession && floatingBubbleView == null) {
                stopSelf()
            }
        }

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

        dragDismissCalculator = DragToDismissCalculator(
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
            bubbleSizePx = bubble.totalViewSizePx
        )

        bubble.listener = object : FloatingBubbleView.BubbleEventListener {
            override fun onBubbleClick() {
                if (miniOverlayHudView != null) {
                    dismissMiniOverlayHud()
                } else if (arOverlayView != null) {
                    dismissArOverlay()
                } else {
                    onFloatingBubbleClicked()
                }
            }

            override fun onBubbleBusyClick(currentState: FloatingBubbleView.BubbleState) {
                cancelActiveTranslation()
                Toast.makeText(this@ScreenTranslateOverlayService,
                    getString(R.string.screen_overlay_cancelled), Toast.LENGTH_SHORT).show()
            }

            override fun onBubbleLongClick() {
                showMiniOverlayHud(params.x, params.y)
            }

            override fun onPositionChanged(x: Int, y: Int) {}

            override fun onDocked(edge: FloatingBubbleView.DockEdge, y: Int) {
                prefs.edit().putInt(KEY_POS_X, params.x).putInt(KEY_POS_Y, y).apply()
            }

            override fun onDragStart() {
                dismissMiniOverlayHud()
                showTrashZone()
            }

            override fun onDragMove(bubbleCenterX: Float, bubbleCenterY: Float) {
                val result = dragDismissCalculator?.evaluateDrag(bubbleCenterX, bubbleCenterY) ?: return
                dismissTrashView?.setMagneticHover(result.isInsideMagneticZone, result.trashScaleFactor)
            }

            override fun onDragRelease(): Boolean {
                val action = dragDismissCalculator?.onRelease()
                val isDismiss = action == DragDismissAction.DISMISS_SERVICE
                if (isDismiss) {
                    hideTrashZone {
                        stopSelf()
                    }
                    floatingBubbleView?.let { b ->
                        b.animate()
                            .alpha(0f)
                            .scaleX(0.2f)
                            .scaleY(0.2f)
                            .setDuration(160L)
                            .withEndAction {
                                runCatching { windowManager?.removeView(b) }
                                floatingBubbleView = null
                            }
                            .start()
                    }
                    return true
                } else {
                    hideTrashZone()
                    return false
                }
            }
        }

        try {
            wm.addView(bubble, params)
            floatingBubbleView = bubble
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding floating bubble view", e)
        }
    }

    private fun showTrashZone() {
        if (dismissTrashView != null) return
        val wm = windowManager ?: return
        val metrics = resources.displayMetrics
        val density = metrics.density

        val trash = DismissTrashView(this)
        val calc = dragDismissCalculator ?: DragToDismissCalculator(
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            density = density,
            bubbleSizePx = (76 * density).toInt()
        ).also { dragDismissCalculator = it }

        val trashParams = WindowManager.LayoutParams(
            trash.totalSizePx,
            trash.totalSizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (calc.trashCenter.x - (trash.totalSizePx / 2f)).toInt()
            y = (calc.trashCenter.y - (trash.totalSizePx / 2f)).toInt()
        }

        try {
            wm.addView(trash, trashParams)
            dismissTrashView = trash
            trash.animateEntrance()
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding DismissTrashView", e)
        }
    }

    private fun hideTrashZone(onComplete: (() -> Unit)? = null) {
        dismissTrashView?.let { trash ->
            trash.animateExit {
                runCatching { windowManager?.removeView(trash) }
                if (dismissTrashView == trash) {
                    dismissTrashView = null
                }
                onComplete?.invoke()
            }
        } ?: onComplete?.invoke()
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
        hud.setState(settings.effectiveScreenTargetLanguage, isLlm)
        hud.listener = object : MiniOverlayHudView.MiniOverlayHudListener {
            override fun onTargetLanguageSelected(language: Language) {
                Log.i(TAG, "Target language changed in HUD: ${language.code}")
                if (settings.screenSyncTargetWithMain) {
                    settings.textTargetLanguage = language
                } else {
                    settings.screenTargetLanguage = language
                }
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

            override fun onSaveScreenshotRequested() {
                dismissMiniOverlayHud()
                captureAndSaveScreenshotWithTranslation()
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

    private var currentSessionToken = 0L
    private var mutexLockedTimestampMs = 0L

    private val watchdogRunnable = Runnable {
        if (translationMutex.isLocked) {
            Log.w(TAG, "Watchdog triggered: Translation pipeline exceeded 10s timeout, forcing recovery")
            recoverToIdle(getString(R.string.screen_overlay_timeout))
        }
    }

    private fun acquireTranslationLock(): Boolean {
        currentSessionToken++
        val now = android.os.SystemClock.elapsedRealtime()
        val acquired = translationMutex.tryLock()
        if (acquired) {
            mutexLockedTimestampMs = now
            mainHandler.removeCallbacks(watchdogRunnable)
            mainHandler.postDelayed(watchdogRunnable, 10_000L)
        }
        return acquired
    }

    private fun releaseTranslationLock() {
        mainHandler.removeCallbacks(watchdogRunnable)
        if (translationMutex.isLocked) {
            try { translationMutex.unlock() } catch (_: IllegalStateException) {}
        }
    }

    private fun cancelActiveTranslation() {
        currentSessionToken++
        activeTranslationJob?.cancel()
        activeTranslationJob = null
        dismissLensScanEffect()
        // The cancelled job's finally block owns the mutex release. Releasing it
        // here allowed a new session to start while the old one was still
        // unwinding, and the old finally could then unlock the new session.
        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
    }

    private fun recoverToIdle(reason: String? = null) {
        currentSessionToken++
        cancelActiveTranslation()
        dismissArOverlay()
        reason?.let { msg ->
            Toast.makeText(this@ScreenTranslateOverlayService, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun onFloatingBubbleClicked() {
        if (arOverlayView != null) {
            dismissArOverlay()
            return
        }

        val a11yService = ScreenAccessibilityService.getInstance()
        if (a11yService != null && ScreenAccessibilityService.isConnected()) {
            captureViaAccessibility(a11yService)
            return
        }

        if (!captureController.isAttached) {
            triggerScreenCapturePermission()
            return
        }

        captureViaMediaProjection()
    }

    private fun captureViaAccessibility(a11y: ScreenAccessibilityService) {
        if (!acquireTranslationLock()) {
            Log.d(TAG, "Translation request dropped: Pipeline already active")
            return
        }

        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.SCANNING)
        setBubbleVisible(false)

        val fastNodes = a11y.extractVisibleTextNodesFast()
        a11y.captureSilentScreenshot(
            onSuccess = { bitmap ->
                setBubbleVisible(true)
                showLensScanEffect()
                activeTranslationJob = serviceScope.launch {
                    try {
                        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.TRANSLATING)
                        processAndShowArTranslation(bitmap = bitmap, directNodes = fastNodes)
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Cancelled by user tap
                    } catch (e: Exception) {
                        Log.e(TAG, "Accessibility direct translation error", e)
                        dismissLensScanEffect()
                        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
                    } finally {
                        releaseTranslationLock()
                        activeTranslationJob = null
                    }
                }
            },
            onError = { err ->
                setBubbleVisible(true)
                if (fastNodes.isNotEmpty()) {
                    showLensScanEffect()
                    activeTranslationJob = serviceScope.launch {
                        try {
                            floatingBubbleView?.setState(FloatingBubbleView.BubbleState.TRANSLATING)
                            processAndShowArTranslation(bitmap = null, directNodes = fastNodes)
                        } catch (_: kotlinx.coroutines.CancellationException) {
                        } catch (e: Exception) {
                            Log.e(TAG, "Accessibility direct translation error", e)
                            dismissLensScanEffect()
                            floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
                        } finally {
                            releaseTranslationLock()
                            activeTranslationJob = null
                        }
                    }
                } else {
                    Log.w(TAG, "Silent screenshot failed ($err), falling back to MediaProjection if available")
                    releaseTranslationLock()
                    dismissLensScanEffect()
                    if (captureController.isAttached) {
                        captureViaMediaProjection()
                    } else {
                        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
                        Toast.makeText(this@ScreenTranslateOverlayService, err, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun captureViaMediaProjection() {
        if (!acquireTranslationLock()) {
            Log.d(TAG, "Translation request dropped: Pipeline already active")
            return
        }

        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.SCANNING)
        setBubbleVisible(false)

        activeTranslationJob = serviceScope.launch {
            try {
                delay(30L)
                val metrics = resources.displayMetrics
                captureController.updateDisplayMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

                val bitmap = captureController.acquireLatestFrame()
                setBubbleVisible(true)
                if (bitmap == null) {
                    floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
                    Toast.makeText(this@ScreenTranslateOverlayService, getString(R.string.screen_overlay_capture_failed), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                showLensScanEffect()
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.TRANSLATING)
                processAndShowArTranslation(bitmap)
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Cancelled by user tap
            } catch (e: Exception) {
                Log.e(TAG, "Capture & Save failed", e)
                dismissLensScanEffect()
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
            } finally {
                setBubbleVisible(true)
                releaseTranslationLock()
                activeTranslationJob = null
            }
        }
    }

    private fun currentDisplaySize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager?.maximumWindowMetrics?.let { metrics ->
                return metrics.bounds.width() to metrics.bounds.height()
            }
        }
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun setBubbleVisible(visible: Boolean) {
        floatingBubbleView?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private fun showLensScanEffect() {
        dismissLensScanEffect()
        val wm = windowManager ?: return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
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

    private suspend fun processAndShowArTranslation(
        bitmap: Bitmap?,
        directNodes: List<com.translive.app.service.accessibility.AccessibilityTextNode>? = null
    ) {
        val isLlm = settings.translationPolicy == TranslationPolicy.LLM_ONLY
        val timeoutMs = if (isLlm) 15_000L else 8_000L
        val timedOut = withTimeoutOrNull(timeoutMs) {
            try {
                val rawCandidates: List<CandidateBlock> = withContext(Dispatchers.Default) {
                    val nativeNodes = if (!directNodes.isNullOrEmpty()) {
                        directNodes.map { CandidateBlock(it.text, it.bounds) }
                    } else {
                        emptyList()
                    }

                    val ocrNodes = if (bitmap != null && (directNodes.isNullOrEmpty() || settings.ocrMode == com.translive.app.data.OcrMode.FULL_SCAN)) {
                        val ocrResult = ocrEngine.recognize(bitmap, "auto")
                        val clusters = ArBoundingBoxClusterer.cluster(ocrResult)
                        clusters.map { CandidateBlock(it.consolidatedText, it.boundingBox) }
                    } else {
                        emptyList()
                    }

                    com.translive.app.engine.CandidateMerger.merge(nativeNodes, ocrNodes)
                }
                coroutineContext.ensureActive()

                val (screenWidth, screenHeight) = currentDisplaySize()
                val screenBounds = Rect(0, 0, screenWidth, screenHeight)
                val candidates = rawCandidates.filter { cand ->
                    ScreenTextNoiseFilter.isTranslatable(cand.rawText, cand.boundingBox, screenBounds)
                }
                lastCandidates = candidates

                if (candidates.isEmpty()) {
                    dismissLensScanEffect()
                    floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
                    Toast.makeText(this@ScreenTranslateOverlayService, getString(R.string.screen_overlay_no_text), Toast.LENGTH_SHORT).show()
                    if (isOneShotSession && floatingBubbleView == null) {
                        stopSelf()
                    }
                    return@withTimeoutOrNull true
                }

                // 1. Establish Screen-Level Consensus Language BEFORE per-block filtering
                val aggregatedSample = candidates
                    .sortedByDescending { it.rawText.length }
                    .take(8)
                    .joinToString(" ") { it.rawText }
                val detectedScreenLang = if (aggregatedSample.isNotBlank()) {
                    languageDetectionEngine.detect(aggregatedSample)
                } else {
                    Language.ENGLISH
                }

                // 2. Resolve Effective Source Language
                val sourceLang = if (settings.isScreenSourceAuto) {
                    detectedScreenLang
                } else {
                    settings.screenSourceLanguage
                }
                val sourceCode = sourceLang.code
                val sourceDisplayCode = if (settings.isScreenSourceAuto) "auto" else sourceCode

                // 3. Resolve Target Language & Smart Inversion Guard
                var targetLang = settings.effectiveScreenTargetLanguage
                var targetCode = targetLang.code

                // If screen dominant language matches target language, auto-invert to user's native language or alternate
                if (sourceCode == targetCode) {
                    val nativeLang = settings.defaultNativeLanguage
                    targetLang = if (sourceCode != nativeLang.code) {
                        nativeLang
                    } else if (sourceCode != "en") {
                        Language.ENGLISH
                    } else {
                        Language.RUSSIAN
                    }
                    targetCode = targetLang.code
                }

                val arBoxes: List<ArTranslatedBox> = translateCandidates(
                    candidates = candidates,
                    requestedSource = if (settings.isScreenSourceAuto) null else sourceLang,
                    targetLanguage = targetLang,
                    isLlm = isLlm,
                    screenLanguage = sourceLang
                )

                lastCapturedBitmap = bitmap
                lastArBoxes = arBoxes

                dismissLensScanEffect()
                if (arBoxes.isEmpty()) {
                    floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
                    Toast.makeText(this@ScreenTranslateOverlayService, getString(R.string.screen_overlay_no_text), Toast.LENGTH_SHORT).show()
                    if (isOneShotSession && floatingBubbleView == null) {
                        stopSelf()
                    }
                    return@withTimeoutOrNull true
                }
                showArOverlay(arBoxes, bitmap, sourceDisplayCode, targetCode)
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.COMPLETE)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AR Translation processing failed", e)
                dismissLensScanEffect()
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
                Toast.makeText(this@ScreenTranslateOverlayService, "Translation error: ${e.message}", Toast.LENGTH_SHORT).show()
                if (isOneShotSession && floatingBubbleView == null) {
                    stopSelf()
                }
                true
            }
        } == null

        if (timedOut) {
            Log.w(TAG, "Translation timed out after ${timeoutMs}ms - recovering to IDLE")
            recoverToIdle(getString(R.string.screen_overlay_timeout))
        }
    }

    /**
     * Translates screen blocks grouped by their detected source language.
     *
     * Previously a single ML Kit translator (the pair of the longest sample) was
     * used for every block, so mixed-language screens were translated in the
     * wrong direction. Each language group now gets its own pair and its own
     * batch, and the result is mapped back to the original reading order.
     */
    private fun isLlmLoaded(): Boolean =
        if (modelRepository.getActiveRuntime() == ModelRuntime.LITERT_LM) {
            liteRtTranslationEngine.isLoaded
        } else {
            translationEngine.isLoaded
        }

    private suspend fun translateLlm(
        text: String,
        source: Language,
        target: Language,
        maxTokens: Int = 384
    ): String =
        if (modelRepository.getActiveRuntime() == ModelRuntime.LITERT_LM) {
            liteRtTranslationEngine.translateSafe(text, source, target)
        } else {
            translationEngine.translateSafe(text, source, target, maxTokens)
        }

    private suspend fun translateCandidates(
        candidates: List<CandidateBlock>,
        requestedSource: Language?,
        targetLanguage: Language,
        isLlm: Boolean,
        screenLanguage: Language? = null
    ): List<ArTranslatedBox> = withContext(Dispatchers.IO) {
        fun isLatinOnly(s: String): Boolean =
            s.any { it in 'A'..'Z' || it in 'a'..'z' } &&
                s.none {
                    it in '\u0600'..'\u06FF' || it in '\u0400'..'\u04FF' ||
                        it in '\u4E00'..'\u9FFF' || it in '\uAC00'..'\uD7AF' ||
                        it in '\u3040'..'\u30FF'
                }

        data class DetectedBlock(val block: CandidateBlock, val language: Language)

        val detected = candidates.map { candidate ->
            val language = if (requestedSource == null) {
                val raw = languageDetectionEngine.detect(candidate.rawText)
                when {
                    isLatinOnly(candidate.rawText) && raw.code in setOf("ar", "fa", "ur") ->
                        screenLanguage ?: Language.ENGLISH
                    candidate.rawText.length < 25 && isLatinOnly(candidate.rawText) &&
                        (screenLanguage == Language.ENGLISH) -> Language.ENGLISH
                    else -> raw
                }
            } else {
                requestedSource
            }
            DetectedBlock(candidate, language)
        }

        val toTranslate = detected.filter { it.language.code != targetLanguage.code }
        if (toTranslate.isEmpty()) return@withContext emptyList()

        val resultByBlock = LinkedHashMap<CandidateBlock, ArTranslatedBox>()
        for ((sourceCode, group) in toTranslate.groupBy { it.language.code }) {
            ensureActive()
            val sourceLanguage = Language.fromCode(sourceCode) ?: continue
            val shielded = group.map { item ->
                if (com.translive.app.engine.entity.BrandEntityShield.isExactEntity(item.block.rawText)) {
                    item.block.rawText to emptyMap<String, String>()
                } else {
                    com.translive.app.engine.entity.BrandEntityShield.shieldEntities(item.block.rawText)
                }
            }
            val texts = shielded.map { it.first }

            val translated: List<String> = when {
                isLlm && isLlmLoaded() -> texts.mapIndexed { index, text ->
                    ensureActive()
                    if (shielded[index].second.isEmpty() &&
                        com.translive.app.engine.entity.BrandEntityShield.isExactEntity(group[index].block.rawText)
                    ) {
                        text
                    } else {
                        translateLlm(text, sourceLanguage, targetLanguage)
                    }
                }

                sourceCode == targetLanguage.code -> texts

                else -> {
                    val activated = fastTranslateEngine.isReadyFor(sourceCode, targetLanguage.code) ||
                        (withTimeoutOrNull(15_000L) {
                            fastTranslateEngine.activateDownloadedPair(sourceCode, targetLanguage.code)
                        } ?: false)

                    if (activated) {
                        // Use the explicit pair instead of the global "current"
                        // translator so concurrent surfaces cannot flip it.
                        fastTranslateEngine.translateLinesFor(texts, sourceCode, targetLanguage.code)
                    } else if (isLlmLoaded()) {
                        texts.map { text ->
                            ensureActive()
                            translateLlm(text, sourceLanguage, targetLanguage)
                        }
                    } else {
                        texts
                    }
                }
            }

            group.forEachIndexed { index, item ->
                val rawTranslation = translated.getOrElse(index) { texts[index] }
                val unshielded = com.translive.app.engine.entity.BrandEntityShield.unshieldEntities(
                    rawTranslation,
                    shielded[index].second
                )
                val finalized = com.translive.app.engine.NmtTechGlossaryPostProcessor.postProcess(unshielded)
                resultByBlock[item.block] = ArTranslatedBox(
                    rawText = item.block.rawText,
                    translatedText = finalized,
                    boundingBox = item.block.boundingBox,
                    sourceLangCode = sourceCode,
                    targetLangCode = targetLanguage.code
                )
            }
        }

        // Preserve the original candidate order, which the overlay uses for the
        // reveal stagger and the reading-order selection.
        candidates.mapNotNull { resultByBlock[it] }
    }

    private fun showArOverlay(
        boxes: List<ArTranslatedBox>,
        frozenBitmap: Bitmap?,
        sourceCode: String = "auto",
        targetCode: String = "ru"
    ) {
        dismissArOverlay()
        activeSourceCode = sourceCode
        activeTargetCode = targetCode
        val wm = windowManager ?: return

        val metrics = resources.displayMetrics
        val screenWidth = frozenBitmap?.width ?: metrics.widthPixels
        val screenHeight = frozenBitmap?.height ?: metrics.heightPixels

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        val overlay = ArTranslateOverlayView(
            context = this,
            rawBoxes = boxes,
            frozenBitmap = frozenBitmap,
            overlayStyle = settings.overlayStyle,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            initialSourceLang = sourceCode,
            initialTargetLang = targetCode,
            onDismiss = {
                dismissArOverlay()
                dismissLensScanEffect()
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
                if (isOneShotSession && floatingBubbleView == null) {
                    stopSelf()
                }
            }
        ).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }
        overlay.onSaveScreenshot = {
            saveScreenshotWithTranslation()
        }
        overlay.onLanguageChanged = { src, tgt ->
            settings.updateScreenLanguagePair(src, tgt)
            retranslateCurrentScreen(src, tgt)
        }

        try {
            overlay.alpha = 0f
            overlay.scaleX = 0.985f
            overlay.scaleY = 0.985f
            wm.addView(overlay, params)
            arOverlayView = overlay
            overlayShownAtMs = android.os.SystemClock.elapsedRealtime()
            overlay.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            overlay.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)

            // Re-elevate floating bubble above AR overlay so user can tap it to dismiss
            floatingBubbleView?.let { bubble ->
                bubbleLayoutParams?.let { bp ->
                    runCatching { wm.removeView(bubble) }
                    wm.addView(bubble, bp)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed adding ArTranslateOverlayView", e)
        }
    }

    private fun retranslateCurrentScreen(newSourceCode: String, newTargetCode: String) {
        settings.updateScreenLanguagePair(newSourceCode, newTargetCode)
        activeSourceCode = newSourceCode
        activeTargetCode = newTargetCode
        val candidates = lastCandidates.takeIf { it.isNotEmpty() }
            ?: lastArBoxes.map { CandidateBlock(it.rawText, it.boundingBox) }
        if (candidates.isEmpty()) return

        retranslateJob?.cancel()
        retranslateJob = serviceScope.launch {
            try {
                val targetLang = Language.fromCode(newTargetCode) ?: Language.RUSSIAN
                val sourceLang = if (newSourceCode == "auto") null else Language.fromCode(newSourceCode)
                val isLlm = settings.translationPolicy == TranslationPolicy.LLM_ONLY
                val arBoxes = translateCandidates(
                    candidates = candidates,
                    requestedSource = sourceLang,
                    targetLanguage = targetLang,
                    isLlm = isLlm
                )

                lastArBoxes = arBoxes
                withContext(Dispatchers.Main) {
                    arOverlayView?.updateBoxes(arBoxes, newSourceCode, newTargetCode)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "In-place re-translation failed", e)
            }
        }
    }

    private fun saveScreenshotWithTranslation() {
        val bitmap = lastCapturedBitmap ?: return
        val boxes = lastArBoxes
        serviceScope.launch {
            val targetCode = activeTargetCode.ifBlank { settings.effectiveScreenTargetLanguage.code }
            val rendered = arOverlayView?.renderComposite()
            val result = if (rendered != null) {
                screenTranslationExporter.exportRendered(rendered, activeSourceCode, targetCode)
            } else {
                screenTranslationExporter.export(bitmap, boxes, activeSourceCode, targetCode)
            }
            when (result) {
                is com.translive.app.engine.export.ScreenExportResult.Success -> {
                    Toast.makeText(this@ScreenTranslateOverlayService, "Saved: ${result.relativePath}", Toast.LENGTH_SHORT).show()
                }
                is com.translive.app.engine.export.ScreenExportResult.Failure -> {
                    Toast.makeText(this@ScreenTranslateOverlayService, "Save failed: ${result.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun captureAndSaveScreenshotWithTranslation() {
        if (!captureController.isAttached) {
            triggerScreenCapturePermission()
            return
        }

        if (!acquireTranslationLock()) return
        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.SCANNING)
        showLensScanEffect()

        activeTranslationJob = serviceScope.launch {
            try {
                delay(30L)
                val metrics = resources.displayMetrics
                captureController.updateDisplayMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)

                val bitmap = captureController.acquireLatestFrame()
                if (bitmap == null) {
                    dismissLensScanEffect()
                    floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
                    Toast.makeText(this@ScreenTranslateOverlayService, getString(R.string.screen_overlay_capture_failed), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.TRANSLATING)
                processAndShowArTranslation(bitmap)
                delay(100L)
                saveScreenshotWithTranslation()
            } catch (_: kotlinx.coroutines.CancellationException) {
                // Cancelled by user tap
            } catch (e: Exception) {
                Log.e(TAG, "Capture & Save failed", e)
                dismissLensScanEffect()
                floatingBubbleView?.setState(FloatingBubbleView.BubbleState.ERROR)
            } finally {
                releaseTranslationLock()
                activeTranslationJob = null
            }
        }
    }

    private fun dismissArOverlay() {
        val overlay = arOverlayView ?: return
        arOverlayView = null
        overlay.dismissOverlay(immediate = true)
        runCatching { windowManager?.removeView(overlay) }
        dismissLensScanEffect()
        floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
    }

    private fun triggerScreenCapturePermission() {
        Log.i(TAG, "Triggering screen capture permission via MainActivity")
        val intent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_REQUEST_SCREEN_CAPTURE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun ensureForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.screen_overlay_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.screen_overlay_notification_title))
            .setContentText(getString(R.string.screen_overlay_notification_text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()

        val fgsType = if (captureController.isAttached) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            fgsType
        )
    }

    override fun onDestroy() {
        _isServiceRunning.value = false
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        hideTrashZone()
        dismissMiniOverlayHud()
        dismissLensScanEffect()
        dismissArOverlay()
        captureController.release()
        lastCapturedBitmap?.let { runCatching { it.recycle() } }
        lastCapturedBitmap = null
        lastArBoxes = emptyList()
        lastCandidates = emptyList()
        floatingBubbleView?.let { view ->
            runCatching { windowManager?.removeView(view) }
        }
        floatingBubbleView = null
        windowManager = null
        if (instanceRef?.get() == this) instanceRef = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "ScreenOverlayService"
        const val ACTION_START = "com.translive.app.action.START_OVERLAY"
        const val ACTION_SHOW_TRANSLATION = "com.translive.app.action.SHOW_SCREEN_TRANSLATION"
        const val ACTION_REQUEST_LIVE_TRANSLATE = "com.translive.app.action.REQUEST_LIVE_TRANSLATE"
        const val ACTION_REQUEST_SCREEN_CAPTURE = "com.translive.app.action.REQUEST_SCREEN_CAPTURE"
        const val ACTION_TRANSLATE_BITMAP = "com.translive.app.action.TRANSLATE_BITMAP"
        const val ACTION_TRANSLATE_NODES = "com.translive.app.action.TRANSLATE_NODES"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_ONE_SHOT_MODE = "extra_one_shot_mode"

        private const val CHANNEL_ID = "screen_translation_overlay"
        private const val NOTIFICATION_ID = 7102
        private const val PREFS_NAME = "parlex_overlay_prefs"
        private const val KEY_POS_X = "button_pos_x"
        private const val KEY_POS_Y = "button_pos_y"

        private var pendingDirectBitmap: Bitmap? = null
        private var pendingDirectNodes: List<com.translive.app.service.accessibility.AccessibilityTextNode>? = null
        private val pendingLock = Any()
        private var instanceRef: java.lang.ref.WeakReference<ScreenTranslateOverlayService>? = null

        fun onWindowStateChanged() {
            val service = instanceRef?.get() ?: return
            service.mainHandler.post {
                if (service.arOverlayView == null) return@post
                // Ignore the window event caused by showing the overlay itself.
                if (android.os.SystemClock.elapsedRealtime() - service.overlayShownAtMs < 500L) return@post
                Log.i(TAG, "Window changed under AR overlay; dismissing stale translation")
                service.dismissArOverlay()
                service.floatingBubbleView?.setState(FloatingBubbleView.BubbleState.IDLE)
            }
        }

        private val _isServiceRunning = MutableStateFlow(false)
        val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

        fun translateScreenshot(context: Context, bitmap: Bitmap) {
            pendingDirectBitmap = bitmap
            val intent = Intent(context, ScreenTranslateOverlayService::class.java).apply {
                action = ACTION_TRANSLATE_BITMAP
                putExtra(EXTRA_ONE_SHOT_MODE, false)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun translateScreenshotOneShot(context: Context, bitmap: Bitmap) {
            pendingDirectBitmap = bitmap
            val intent = Intent(context, ScreenTranslateOverlayService::class.java).apply {
                action = ACTION_TRANSLATE_BITMAP
                putExtra(EXTRA_ONE_SHOT_MODE, true)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun translateNodes(
            context: Context,
            nodes: List<com.translive.app.service.accessibility.AccessibilityTextNode>,
            bitmap: Bitmap? = null
        ) {
            pendingDirectNodes = nodes
            pendingDirectBitmap = bitmap
            val intent = Intent(context, ScreenTranslateOverlayService::class.java).apply {
                action = ACTION_TRANSLATE_NODES
                putExtra(EXTRA_ONE_SHOT_MODE, false)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun translateNodesOneShot(
            context: Context,
            nodes: List<com.translive.app.service.accessibility.AccessibilityTextNode>,
            bitmap: Bitmap? = null
        ) {
            pendingDirectNodes = nodes
            pendingDirectBitmap = bitmap
            val intent = Intent(context, ScreenTranslateOverlayService::class.java).apply {
                action = ACTION_TRANSLATE_NODES
                putExtra(EXTRA_ONE_SHOT_MODE, true)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun start(context: Context) {
            val intent = Intent(context, ScreenTranslateOverlayService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ScreenTranslateOverlayService::class.java)
            context.stopService(intent)
        }

        fun startWithProjection(context: Context, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenTranslateOverlayService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, resultData)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        @Suppress("DEPRECATION")
        private fun Intent.parcelableIntent(key: String): Intent? {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                getParcelableExtra(key, Intent::class.java)
            } else {
                getParcelableExtra(key)
            }
        }
    }
}
