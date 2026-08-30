package com.translive.app.service

import android.animation.ValueAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import android.content.pm.ServiceInfo
import com.translive.app.R
import com.translive.app.data.SettingsRepository
import com.translive.app.data.model.Language
import com.translive.app.engine.FastTranslateEngine
import com.translive.app.engine.OcrEngine
import com.translive.app.i18n.LocalizedTextProvider
import com.translive.app.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject
import kotlin.math.abs

/**
 * Interactive floating button and in-place translation overlay service.
 *
 * Capabilities:
 * 1. Draggable, snap-to-edge floating action button with position persistence.
 * 2. Long-press popup menu for quick actions (Translate, Language Info, Close).
 * 3. In-place overlay translation card that displays OCR + Fast NMT results
 *    directly over third-party applications without switching context.
 */
@AndroidEntryPoint
class ScreenTranslateOverlayService : Service() {

    @Inject lateinit var ocrEngine: OcrEngine
    @Inject lateinit var fastTranslateEngine: FastTranslateEngine
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var texts: LocalizedTextProvider

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var floatingButtonView: View? = null
    private var buttonLayoutParams: WindowManager.LayoutParams? = null
    private var contextMenuView: View? = null
    private var translationCardView: View? = null

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureForeground()

        when (intent?.action) {
            ACTION_SHOW_TRANSLATION -> {
                val imageUri = intent.data
                if (imageUri != null) {
                    processAndShowTranslation(imageUri)
                }
            }
            else -> {
                if (floatingButtonView == null) {
                    showFloatingButton()
                }
            }
        }
        return START_STICKY
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
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    // =========================================================================
    // 1. Floating Draggable Button
    // =========================================================================

    private fun showFloatingButton() {
        if (floatingButtonView != null) return

        val density = resources.displayMetrics.density
        val buttonSize = (52 * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val savedX = prefs.getInt(KEY_POS_X, screenWidth - buttonSize - (16 * density).toInt())
        val savedY = prefs.getInt(KEY_POS_Y, screenHeight / 2 - buttonSize / 2)

        val params = WindowManager.LayoutParams(
            buttonSize,
            buttonSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceIn(0, screenWidth - buttonSize)
            y = savedY.coerceIn(0, screenHeight - buttonSize)
        }
        buttonLayoutParams = params

        val button = TextView(this).apply {
            text = "文"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(230, 80, 70, 180))
                setStroke((2 * density).toInt(), Color.argb(180, 130, 120, 230))
            }
            elevation = 8 * density
        }

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = 10 * density

        val longPressRunnable = Runnable {
            if (!isDragging) {
                showContextMenu(params.x, params.y)
            }
        }

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    mainHandler.postDelayed(longPressRunnable, 500L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }
                    if (isDragging) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        runCatching { windowManager?.updateViewLayout(button, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!isDragging) {
                        // Click action -> trigger screen capture
                        triggerScreenCapture()
                    } else {
                        // Snap to nearest edge
                        snapToEdge(params, buttonSize)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(button, params)
        floatingButtonView = button
    }

    private fun snapToEdge(params: WindowManager.LayoutParams, buttonSize: Int) {
        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val margin = (12 * density).toInt()
        val targetX = if (params.x + buttonSize / 2 < screenWidth / 2) {
            margin
        } else {
            screenWidth - buttonSize - margin
        }

        val startX = params.x
        val animator = ValueAnimator.ofInt(startX, targetX).apply {
            duration = 200L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                runCatching { windowManager?.updateViewLayout(floatingButtonView, params) }
            }
        }
        animator.start()

        prefs.edit()
            .putInt(KEY_POS_X, targetX)
            .putInt(KEY_POS_Y, params.y)
            .apply()
    }

    private fun triggerScreenCapture() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_REQUEST_SCREEN_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    // =========================================================================
    // 2. Long-press Context Menu Popup
    // =========================================================================

    private fun showContextMenu(buttonX: Int, buttonY: Int) {
        dismissContextMenu()

        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val menuWidth = (220 * density).toInt()
        val menuParams = WindowManager.LayoutParams(
            menuWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (buttonX < screenWidth / 2) buttonX + (60 * density).toInt() else buttonX - menuWidth - (10 * density).toInt()
            y = buttonY.coerceIn((20 * density).toInt(), screenHeight - (200 * density).toInt())
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(Color.argb(245, 30, 30, 48))
                setStroke((1 * density).toInt(), Color.argb(120, 80, 80, 120))
            }
            elevation = 12 * density
        }

        // 1. Action: Translate Screen
        val translateItem = createMenuItem("📸 " + getString(R.string.screen_overlay_menu_translate), density) {
            dismissContextMenu()
            triggerScreenCapture()
        }
        container.addView(translateItem)

        // Divider
        container.addView(createDivider(density))

        // 2. Language Info: RU -> EN
        val src = settings.textSourceLanguage
        val tgt = settings.textTargetLanguage
        val langInfo = createMenuItem("🌐 ${src.displayName} → ${tgt.displayName}", density) {
            // Informational chip
        }
        container.addView(langInfo)

        // Divider
        container.addView(createDivider(density))

        // 3. Action: Close Floating Button
        val closeItem = createMenuItem("✕ " + getString(R.string.screen_overlay_menu_close), density, Color.rgb(255, 120, 120)) {
            dismissContextMenu()
            stopSelf()
        }
        container.addView(closeItem)

        // Close on tap outside
        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismissContextMenu()
                true
            } else false
        }

        windowManager?.addView(container, menuParams)
        contextMenuView = container
    }

    private fun createMenuItem(label: String, density: Float, textColor: Int = Color.WHITE, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(textColor)
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createDivider(density: Float): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                setMargins(0, (4 * density).toInt(), 0, (4 * density).toInt())
            }
            setBackgroundColor(Color.argb(60, 255, 255, 255))
        }
    }

    private fun dismissContextMenu() {
        contextMenuView?.let { runCatching { windowManager?.removeView(it) } }
        contextMenuView = null
    }

    // =========================================================================
    // 3. In-Place Translation Result Card
    // =========================================================================

    private fun processAndShowTranslation(uri: Uri) {
        dismissTranslationCard()

        val density = resources.displayMetrics.density
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels

        val cardWidth = (screenWidth * 0.92f).toInt()
        val cardParams = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * density).toInt(), (14 * density).toInt(), (16 * density).toInt(), (14 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(Color.argb(250, 24, 24, 38))
                setStroke((1 * density).toInt(), Color.argb(120, 100, 90, 200))
            }
            elevation = 16 * density
        }

        // Header: Title & Close '✕'
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val src = settings.textSourceLanguage
        val tgt = settings.textTargetLanguage
        val title = TextView(this).apply {
            text = "Parlex • ${src.displayName} → ${tgt.displayName}"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(180, 170, 255))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(Color.argb(180, 255, 255, 255))
            setPadding((8 * density).toInt(), 0, (4 * density).toInt(), 0)
            setOnClickListener { dismissTranslationCard() }
        }
        header.addView(title)
        header.addView(closeBtn)
        cardLayout.addView(header)

        // Progress bar container
        val progressContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (16 * density).toInt(), 0, (16 * density).toInt())
        }
        val progressBar = ProgressBar(this).apply {
            layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
        }
        val statusText = TextView(this).apply {
            text = "  " + getString(R.string.screen_overlay_processing)
            textSize = 14f
            setTextColor(Color.WHITE)
        }
        progressContainer.addView(progressBar)
        progressContainer.addView(statusText)
        cardLayout.addView(progressContainer)

        // Content ScrollView (initially hidden)
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (screenHeight * 0.45f).toInt()
            )
            visibility = View.GONE
        }
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(contentLayout)
        cardLayout.addView(scrollView)

        // Bottom Action Bar (initially hidden)
        val actionsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (12 * density).toInt(), 0, 0)
            visibility = View.GONE
        }
        cardLayout.addView(actionsLayout)

        windowManager?.addView(cardLayout, cardParams)
        translationCardView = cardLayout

        // Run OCR + Fast NMT in background
        serviceScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        BitmapFactory.decodeStream(stream)
                    }
                }

                if (bitmap == null) {
                    statusText.text = "  " + getString(R.string.screen_overlay_no_text)
                    progressBar.visibility = View.GONE
                    return@launch
                }

                // 1. OCR Recognition
                val ocrResult = withContext(Dispatchers.Default) {
                    ocrEngine.recognize(bitmap, src.code)
                }
                val rawLines = ocrResult.blocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotBlank() }

                if (rawLines.isEmpty()) {
                    statusText.text = "  " + getString(R.string.screen_overlay_no_text)
                    progressBar.visibility = View.GONE
                    return@launch
                }

                // 2. Fast NMT Translation
                fastTranslateEngine.activateDownloadedPair(src.code, tgt.code)
                val translatedLines = withContext(Dispatchers.IO) {
                    fastTranslateEngine.translateLines(rawLines)
                }

                val fullTranslatedText = translatedLines.joinToString("\n")

                // Update UI
                progressContainer.visibility = View.GONE
                scrollView.visibility = View.VISIBLE
                actionsLayout.visibility = View.VISIBLE

                contentLayout.removeAllViews()
                rawLines.forEachIndexed { index, raw ->
                    val trans = translatedLines.getOrElse(index) { raw }

                    val blockView = LinearLayout(this@ScreenTranslateOverlayService).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 8 * density
                            setColor(Color.argb(80, 40, 40, 60))
                        }
                    }
                    val rawText = TextView(this@ScreenTranslateOverlayService).apply {
                        text = raw
                        textSize = 12f
                        setTextColor(Color.argb(160, 200, 200, 220))
                    }
                    val transText = TextView(this@ScreenTranslateOverlayService).apply {
                        text = trans
                        textSize = 14f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Color.WHITE)
                    }
                    blockView.addView(rawText)
                    blockView.addView(transText)
                    contentLayout.addView(blockView)

                    // Spacer between blocks
                    contentLayout.addView(View(this@ScreenTranslateOverlayService).apply {
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (6 * density).toInt())
                    })
                }

                // Actions: Copy & Open in App
                actionsLayout.removeAllViews()

                val copyBtn = TextView(this@ScreenTranslateOverlayService).apply {
                    text = "📋 " + getString(R.string.screen_overlay_copy_action)
                    textSize = 13f
                    setTextColor(Color.rgb(180, 170, 255))
                    setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6 * density
                        setColor(Color.argb(60, 80, 70, 180))
                    }
                    setOnClickListener {
                        val clipboard = getSystemService(ClipboardManager::class.java)
                        clipboard.setPrimaryClip(ClipData.newPlainText("Parlex Translation", fullTranslatedText))
                        Toast.makeText(this@ScreenTranslateOverlayService, getString(R.string.screen_overlay_copied), Toast.LENGTH_SHORT).show()
                    }
                }
                actionsLayout.addView(copyBtn)

                actionsLayout.addView(View(this@ScreenTranslateOverlayService).apply {
                    layoutParams = LinearLayout.LayoutParams((8 * density).toInt(), 1)
                })

                val openAppBtn = TextView(this@ScreenTranslateOverlayService).apply {
                    text = "🔍 " + getString(R.string.screen_overlay_open_app_action)
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = 6 * density
                        setColor(Color.rgb(80, 70, 180))
                    }
                    setOnClickListener {
                        dismissTranslationCard()
                        val openIntent = Intent(this@ScreenTranslateOverlayService, MainActivity::class.java)
                            .setAction(ScreenCaptureService.ACTION_CAPTURE_COMPLETE)
                            .setData(uri)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(openIntent)
                    }
                }
                actionsLayout.addView(openAppBtn)

            } catch (e: Exception) {
                statusText.text = "  Error: ${e.message}"
                progressBar.visibility = View.GONE
            }
        }
    }

    private fun dismissTranslationCard() {
        translationCardView?.let { runCatching { windowManager?.removeView(it) } }
        translationCardView = null
    }

    override fun onDestroy() {
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        dismissContextMenu()
        dismissTranslationCard()
        floatingButtonView?.let { runCatching { windowManager?.removeView(it) } }
        floatingButtonView = null
        windowManager = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_REQUEST_SCREEN_CAPTURE = "com.translive.app.action.REQUEST_SCREEN_CAPTURE"
        const val ACTION_SHOW_TRANSLATION = "com.translive.app.action.SHOW_SCREEN_TRANSLATION"

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

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenTranslateOverlayService::class.java))
        }
    }
}
