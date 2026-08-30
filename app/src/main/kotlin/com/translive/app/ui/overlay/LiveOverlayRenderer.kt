package com.translive.app.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.translive.app.data.model.Language
import com.translive.app.engine.LiveTextBlock
import com.translive.app.engine.LiveTranslationFrame
import com.translive.app.service.model.HudAction
import com.translive.app.service.model.HudStatus
import com.translive.app.service.model.HudUiState
import com.translive.app.service.model.ScreenTranslateMode
import java.util.*
import kotlin.math.abs

/**
 * High-performance, zero-flicker floating AR overlay and interactive HUD controller
 * for continuous live screen auto-translation and on-demand single shot captures.
 */
class LiveOverlayRenderer(
    private val context: Context,
    private val onAction: (HudAction) -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var arOverlayView: ArOverlayCanvasView? = null
    private var hudPillView: View? = null
    private var miniPickerView: View? = null

    private var arLayoutParams: WindowManager.LayoutParams? = null
    private var hudLayoutParams: WindowManager.LayoutParams? = null

    private var currentState = HudUiState()

    private var statusDotView: View? = null
    private var statusHaloView: View? = null
    private var modeButton: TextView? = null
    private var singleShotButton: TextView? = null
    private var langTextView: TextView? = null
    private var pauseBtn: TextView? = null
    private var interactiveBtn: TextView? = null

    private var haloAnimator: ValueAnimator? = null
    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    fun show(initialState: HudUiState) {
        this.currentState = initialState
        createArOverlay()
        createHudPill()
        startPulseAnimation()
    }

    fun updateState(state: HudUiState) {
        this.currentState = state
        mainHandler.post {
            updateHudVisuals()
        }
    }

    fun updateFrame(frame: LiveTranslationFrame) {
        mainHandler.post {
            arOverlayView?.updateBlocks(frame.blocks)
        }
    }

    fun clearArOverlay() {
        mainHandler.post {
            arOverlayView?.updateBlocks(emptyList())
        }
    }

    fun dismiss() {
        mainHandler.post {
            haloAnimator?.cancel()
            haloAnimator = null

            tts?.stop()
            tts?.shutdown()
            tts = null

            dismissMiniPicker()

            arOverlayView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {}
            }
            arOverlayView = null

            hudPillView?.let {
                try {
                    windowManager.removeView(it)
                } catch (_: Exception) {}
            }
            hudPillView = null
        }
    }

    private fun createArOverlay() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        arLayoutParams = params

        val canvasView = ArOverlayCanvasView(context) { clickedBlock ->
            if (currentState.isInteractiveMode) {
                showBlockActionToast(clickedBlock)
            }
        }
        arOverlayView = canvasView

        try {
            windowManager.addView(canvasView, params)
        } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun createHudPill() {
        val dp = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (60 * dp).toInt()
        }
        hudLayoutParams = params

        val pillLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            background = createPillBackground(dp)
            elevation = 16f
        }

        // 1. Status Indicator Dot + Pulsing Halo Container
        val statusBox = FrameLayout(context).apply {
            val size = (18 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (8 * dp).toInt()
            }
        }

        val halo = View(context).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            background = createDotDrawable(Color.parseColor("#00E676"))
            alpha = 0.4f
        }
        statusHaloView = halo

        val dot = View(context).apply {
            val dotSize = (10 * dp).toInt()
            layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
            background = createDotDrawable(Color.parseColor("#00E676"))
        }
        statusDotView = dot

        statusBox.addView(halo)
        statusBox.addView(dot)

        // 2. Mode Switch Button (Auto-Live vs Single)
        val modeToggle = TextView(context).apply {
            text = if (currentState.mode == ScreenTranslateMode.AUTO_LIVE) "⚡ Auto" else "📸 Single"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding((6 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
            background = createChipBackground(dp, Color.parseColor("#33FFFFFF"))
            setOnClickListener {
                val nextMode = if (currentState.mode == ScreenTranslateMode.AUTO_LIVE) {
                    ScreenTranslateMode.SINGLE_SHOT
                } else {
                    ScreenTranslateMode.AUTO_LIVE
                }
                onAction(HudAction.SetMode(nextMode))
            }
        }
        modeButton = modeToggle

        // 3. Single Shot Capture Action Button (Visible only in SINGLE_SHOT mode)
        val shotBtn = TextView(context).apply {
            text = "🎯"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding((6 * dp).toInt(), (2 * dp).toInt(), (6 * dp).toInt(), (2 * dp).toInt())
            visibility = if (currentState.mode == ScreenTranslateMode.SINGLE_SHOT) View.VISIBLE else View.GONE
            setOnClickListener {
                onAction(HudAction.TriggerSingleShot)
            }
        }
        singleShotButton = shotBtn

        // 4. Language Selector Chip
        val langText = TextView(context).apply {
            text = formatLangLabel()
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding((6 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            setOnClickListener {
                toggleMiniPicker(params.x, params.y + (48 * dp).toInt())
            }
        }
        langTextView = langText

        // 5. Pause / Play button
        val pause = TextView(context).apply {
            text = if (currentState.isPaused) "▶" else "⏸"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding((4 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            setOnClickListener {
                onAction(HudAction.TogglePause)
            }
        }
        pauseBtn = pause

        // 6. Interactive Mode Toggle (Touch-through vs Touch-to-copy)
        val modeTouch = TextView(context).apply {
            text = if (currentState.isInteractiveMode) "✋" else "👆"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding((4 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            setOnClickListener {
                onAction(HudAction.ToggleInteractiveMode)
            }
        }
        interactiveBtn = modeTouch

        // 7. Close button
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#FF5252"))
            setTypeface(null, Typeface.BOLD)
            setPadding((6 * dp).toInt(), 0, (2 * dp).toInt(), 0)
            setOnClickListener {
                onAction(HudAction.CloseService)
            }
        }

        pillLayout.addView(statusBox)
        pillLayout.addView(modeToggle)
        pillLayout.addView(shotBtn)
        pillLayout.addView(langText)
        pillLayout.addView(pause)
        pillLayout.addView(modeTouch)
        pillLayout.addView(closeBtn)

        setupPillDragging(pillLayout, params)

        hudPillView = pillLayout
        try {
            windowManager.addView(pillLayout, params)
        } catch (_: Exception) {}
    }

    private fun formatLangLabel(): String {
        val src = if (currentState.isSourceAuto) "🌐 Auto" else currentState.sourceLanguage.flag
        return "$src → ${currentState.targetLanguage.flag}"
    }

    private fun updateHudVisuals() {
        modeButton?.text = if (currentState.mode == ScreenTranslateMode.AUTO_LIVE) "⚡ Auto" else "📸 Single"
        singleShotButton?.visibility = if (currentState.mode == ScreenTranslateMode.SINGLE_SHOT) View.VISIBLE else View.GONE
        langTextView?.text = formatLangLabel()
        pauseBtn?.text = if (currentState.isPaused) "▶" else "⏸"
        interactiveBtn?.text = if (currentState.isInteractiveMode) "✋" else "👆"

        val statusColor = when (currentState.status) {
            HudStatus.IDLE -> Color.parseColor("#9E9E9E")
            HudStatus.MONITORING -> Color.parseColor("#00E676")
            HudStatus.STABILIZING -> Color.parseColor("#FFA000")
            HudStatus.TRANSLATING -> Color.parseColor("#80D8FF")
            HudStatus.PAUSED -> Color.parseColor("#FF9100")
            HudStatus.ERROR -> Color.parseColor("#FF1744")
        }

        statusDotView?.background = createDotDrawable(statusColor)
        statusHaloView?.background = createDotDrawable(statusColor)

        applyTouchMode(currentState.isInteractiveMode)
    }

    private fun startPulseAnimation() {
        haloAnimator?.cancel()
        haloAnimator = ValueAnimator.ofFloat(1.0f, 1.4f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val scale = anim.animatedValue as Float
                val alpha = (1.4f - scale) * 1.5f
                statusHaloView?.scaleX = scale
                statusHaloView?.scaleY = scale
                statusHaloView?.alpha = alpha.coerceIn(0.1f, 0.6f)
            }
            start()
        }
    }

    private fun applyTouchMode(interactive: Boolean) {
        val params = arLayoutParams ?: return
        val view = arOverlayView ?: return

        if (interactive) {
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            windowManager.updateViewLayout(view, params)
        } catch (_: Exception) {}
    }

    private fun showBlockActionToast(block: LiveTextBlock) {
        val textToCopy = block.translatedText ?: block.rawText
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Parlex Translation", textToCopy))

        tts?.speak(textToCopy, TextToSpeech.QUEUE_FLUSH, null, "live_trans_${System.currentTimeMillis()}")
        Toast.makeText(context, "Copied: $textToCopy", Toast.LENGTH_SHORT).show()
    }

    private fun toggleMiniPicker(hudX: Int, hudY: Int) {
        if (miniPickerView != null) {
            dismissMiniPicker()
        } else {
            showMiniPicker(hudX, hudY)
        }
    }

    private fun dismissMiniPicker() {
        miniPickerView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        miniPickerView = null
    }

    private fun showMiniPicker(x: Int, y: Int) {
        val dp = context.resources.displayMetrics.density
        val screenWidth = context.resources.displayMetrics.widthPixels

        val pickerParams = WindowManager.LayoutParams(
            (260 * dp).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceIn((10 * dp).toInt(), screenWidth - (270 * dp).toInt())
            this.y = y
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
            background = createPillBackground(dp)
            elevation = 20f
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val autoBtn = TextView(context).apply {
            text = if (currentState.isSourceAuto) "✓ Auto-Detect" else "Auto-Detect"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding((8 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
            background = createChipBackground(dp, if (currentState.isSourceAuto) Color.parseColor("#4CAF50") else Color.parseColor("#33FFFFFF"))
            setOnClickListener {
                onAction(HudAction.SelectSourceLanguage(currentState.sourceLanguage, isAuto = true))
                dismissMiniPicker()
            }
        }

        val swapBtn = TextView(context).apply {
            text = "⇄"
            textSize = 16f
            setTextColor(Color.WHITE)
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), 0)
            setOnClickListener {
                onAction(HudAction.SwapLanguages)
                dismissMiniPicker()
            }
        }

        headerRow.addView(autoBtn)
        headerRow.addView(swapBtn)
        container.addView(headerRow)

        val pinned = listOf(
            Language.RUSSIAN, Language.ENGLISH, Language.CHINESE_SIMPLIFIED,
            Language.JAPANESE, Language.KOREAN, Language.VIETNAMESE,
            Language.GERMAN, Language.FRENCH, Language.SPANISH
        )

        val targetLabel = TextView(context).apply {
            text = "Target Language:"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, (8 * dp).toInt(), 0, (4 * dp).toInt())
        }
        container.addView(targetLabel)

        val grid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        var row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        for ((index, lang) in pinned.withIndex()) {
            val isSelected = currentState.targetLanguage == lang
            val chip = TextView(context).apply {
                text = "${lang.flag} ${lang.displayName}"
                textSize = 11f
                setTextColor(Color.WHITE)
                setPadding((6 * dp).toInt(), (4 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt())
                background = createChipBackground(dp, if (isSelected) Color.parseColor("#3F51B5") else Color.parseColor("#22FFFFFF"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins((2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt(), (2 * dp).toInt())
                }
                setOnClickListener {
                    onAction(HudAction.SelectTargetLanguage(lang))
                    dismissMiniPicker()
                }
            }
            row.addView(chip)

            if ((index + 1) % 3 == 0 || index == pinned.size - 1) {
                grid.addView(row)
                row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            }
        }
        container.addView(grid)

        container.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                dismissMiniPicker()
                true
            } else false
        }

        miniPickerView = container
        try {
            windowManager.addView(container, pickerParams)
        } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPillDragging(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isMoving = false
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isMoving = false
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isMoving = true
                    }
                    if (isMoving) {
                        params.x = initialX + dx
                        params.y = initialY + dy
                        dismissMiniPicker()
                        try {
                            windowManager.updateViewLayout(view, params)
                        } catch (_: Exception) {}
                    }
                    isMoving
                }
                else -> false
            }
        }
    }

    private fun createPillBackground(dp: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 24 * dp
            setColor(Color.parseColor("#E61E1E2C"))
            setStroke((1 * dp).toInt(), Color.parseColor("#44FFFFFF"))
        }
    }

    private fun createChipBackground(dp: Float, color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12 * dp
            setColor(color)
        }
    }

    private fun createDotDrawable(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
    }

    /**
     * Canvas view rendering translated boxes directly over source text.
     */
    private class ArOverlayCanvasView(
        context: Context,
        private val onBlockClicked: (LiveTextBlock) -> Unit
    ) : View(context) {

        private var activeBlocks: List<LiveTextBlock> = emptyList()
        private var alphaAnimator: ValueAnimator? = null
        private var currentAlpha = 1f

        private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#EE181824") // Deep dark high-contrast background
            style = Paint.Style.FILL
        }

        private val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#3380D8FF")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setShadowLayer(4f, 0f, 2f, Color.BLACK)
        }

        private val transliterationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#FF80D8FF")
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
        }

        fun updateBlocks(newBlocks: List<LiveTextBlock>) {
            activeBlocks = newBlocks
            alphaAnimator?.cancel()
            alphaAnimator = ValueAnimator.ofFloat(0.3f, 1f).apply {
                duration = 200
                addUpdateListener {
                    currentAlpha = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (activeBlocks.isEmpty()) return

            val savedAlpha = boxPaint.alpha
            boxPaint.alpha = (238 * currentAlpha).toInt()
            textPaint.alpha = (255 * currentAlpha).toInt()
            transliterationPaint.alpha = (255 * currentAlpha).toInt()

            val cornerRadius = 8f
            val paddingHorizontal = 12f
            val paddingVertical = 6f

            for (block in activeBlocks) {
                val translation = block.translatedText ?: continue
                val bounds = block.bounds

                val boxRect = RectF(
                    bounds.left.toFloat() - paddingHorizontal,
                    bounds.top.toFloat() - paddingVertical,
                    bounds.right.toFloat() + paddingHorizontal,
                    bounds.bottom.toFloat() + paddingVertical
                )

                // Draw background box
                canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, boxPaint)
                canvas.drawRoundRect(boxRect, cornerRadius, cornerRadius, boxBorderPaint)

                // Dynamically fit text height
                val targetHeight = bounds.height().toFloat()
                textPaint.textSize = (targetHeight * 0.7f).coerceIn(24f, 44f)

                // Draw translated text
                val textY = bounds.bottom.toFloat() - (bounds.height() * 0.2f)
                canvas.drawText(translation, bounds.left.toFloat(), textY, textPaint)

                // Draw transliteration if available
                block.transliteration?.let { trans ->
                    transliterationPaint.textSize = textPaint.textSize * 0.7f
                    canvas.drawText(
                        trans,
                        bounds.left.toFloat(),
                        bounds.top.toFloat() - 4f,
                        transliterationPaint
                    )
                }
            }

            boxPaint.alpha = savedAlpha
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.action == MotionEvent.ACTION_UP) {
                val touchX = event.x.toInt()
                val touchY = event.y.toInt()
                val clicked = activeBlocks.find {
                    touchX >= it.bounds.left && touchX <= it.bounds.right &&
                            touchY >= it.bounds.top && touchY <= it.bounds.bottom
                }
                if (clicked != null) {
                    onBlockClicked(clicked)
                    return true
                }
            }
            return super.onTouchEvent(event)
        }
    }
}
