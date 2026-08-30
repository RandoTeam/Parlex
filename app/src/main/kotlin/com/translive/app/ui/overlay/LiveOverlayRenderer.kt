package com.translive.app.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.translive.app.R
import com.translive.app.data.model.Language
import com.translive.app.engine.LiveTextBlock
import com.translive.app.engine.LiveTranslationFrame
import kotlin.math.abs
import kotlin.math.max

/**
 * High-performance, zero-flicker floating AR overlay renderer for live screen translation.
 *
 * Manages:
 * 1. Full-screen touch-through AR Canvas view (translates bounding boxes in-place).
 * 2. Floating draggable compact HUD pill (controls status, interactive mode, language, and closing).
 */
class LiveOverlayRenderer(
    private val context: Context,
    private val onPauseResume: (isPaused: Boolean) -> Unit,
    private val onClose: () -> Unit
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var arOverlayView: ArOverlayCanvasView? = null
    private var hudPillView: View? = null

    private var isInteractiveMode = false
    private var isPaused = false
    private var sourceLanguage: Language = Language.RUSSIAN
    private var targetLanguage: Language = Language.ENGLISH

    private var arLayoutParams: WindowManager.LayoutParams? = null
    private var hudLayoutParams: WindowManager.LayoutParams? = null
    private var langTextView: TextView? = null

    fun show(source: Language, target: Language) {
        this.sourceLanguage = source
        this.targetLanguage = target

        createArOverlay()
        createHudPill()
    }

    fun updateFrame(frame: LiveTranslationFrame) {
        mainHandler.post {
            arOverlayView?.updateBlocks(frame.blocks)
        }
    }

    fun updateLanguages(source: Language, target: Language) {
        this.sourceLanguage = source
        this.targetLanguage = target
        mainHandler.post {
            langTextView?.text = "${source.flag} → ${target.flag}"
        }
    }

    fun dismiss() {
        mainHandler.post {
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
            if (isInteractiveMode) {
                copyToClipboard(clickedBlock)
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

        // 1. Status Indicator (Live dot)
        val statusDot = View(context).apply {
            val size = (10 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                marginEnd = (8 * dp).toInt()
            }
            background = createDotDrawable(Color.parseColor("#00E676"))
        }

        // 2. Language Tag
        val langText = TextView(context).apply {
            text = "${sourceLanguage.flag} → ${targetLanguage.flag}"
            textSize = 12f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, (10 * dp).toInt(), 0)
        }
        langTextView = langText

        // 3. Pause / Play button
        val pauseBtn = TextView(context).apply {
            text = "⏸"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding((4 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            setOnClickListener {
                isPaused = !isPaused
                text = if (isPaused) "▶" else "⏸"
                statusDot.background = createDotDrawable(
                    if (isPaused) Color.parseColor("#FFA000") else Color.parseColor("#00E676")
                )
                onPauseResume(isPaused)
            }
        }

        // 4. Interactive Mode toggle (Touch-through vs Touch-to-copy)
        val modeBtn = TextView(context).apply {
            text = "👆"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding((4 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            setOnClickListener {
                isInteractiveMode = !isInteractiveMode
                text = if (isInteractiveMode) "✋" else "👆"
                toggleInteractiveMode(isInteractiveMode)
                Toast.makeText(
                    context,
                    if (isInteractiveMode) "Интерактивный режим (нажмите на блок для копирования)"
                    else "Режим прозрачного касания",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        // 5. Close button
        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 14f
            setTextColor(Color.parseColor("#FF5252"))
            setTypeface(null, Typeface.BOLD)
            setPadding((6 * dp).toInt(), 0, (2 * dp).toInt(), 0)
            setOnClickListener {
                onClose()
            }
        }

        pillLayout.addView(statusDot)
        pillLayout.addView(langText)
        pillLayout.addView(pauseBtn)
        pillLayout.addView(modeBtn)
        pillLayout.addView(closeBtn)

        // Dragging support for HUD Pill
        setupPillDragging(pillLayout, params)

        hudPillView = pillLayout
        try {
            windowManager.addView(pillLayout, params)
        } catch (_: Exception) {}
    }

    private fun toggleInteractiveMode(interactive: Boolean) {
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

    private fun copyToClipboard(block: LiveTextBlock) {
        val textToCopy = block.translatedText ?: block.rawText
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Parlex Live Translate", textToCopy))
        Toast.makeText(context, "Скопировано: $textToCopy", Toast.LENGTH_SHORT).show()
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

    private fun createPillBackground(dp: Float): android.graphics.drawable.Drawable {
        val shape = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 24 * dp
            setColor(Color.parseColor("#E61E1E2C")) // Frosted dark
            setStroke((1 * dp).toInt(), Color.parseColor("#44FFFFFF"))
        }
        return shape
    }

    private fun createDotDrawable(color: Int): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
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
