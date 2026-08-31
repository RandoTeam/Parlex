package com.translive.app.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.speech.tts.TextToSpeech
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.*
import kotlin.math.max

data class ArTranslatedBox(
    val rawText: String,
    val translatedText: String,
    val boundingBox: Rect,
    val sourceLangCode: String = "auto",
    val targetLangCode: String = "ru"
)

/**
 * Fullscreen In-Place AR Screen Translation Overlay.
 *
 * Renders translated text boxes directly over the original on-screen coordinates
 * with a subtle translucent dark backdrop, tap-to-inspect / copy / speak actions,
 * and tap-outside to dismiss.
 */
@SuppressLint("ViewConstructor")
class ArTranslateOverlayView @JvmOverloads constructor(
    context: Context,
    private val boxes: List<ArTranslatedBox>,
    private val onDismiss: () -> Unit,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density = context.resources.displayMetrics.density
    private var tts: TextToSpeech? = null
    private var activeToolbar: View? = null

    private var currentAlpha = 0f
    private var alphaAnimator: ValueAnimator? = null

    private val bgPaint = Paint().apply {
        color = Color.argb(140, 10, 10, 18) // Semi-transparent subtle dim backdrop
        style = Paint.Style.FILL
    }

    private val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE1A1A28") // High-contrast deep dark card
        style = Paint.Style.FILL
    }

    private val boxStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#807E78E6") // Subtle violet/cyan accent border
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f * density, 0f, 2f * density, Color.BLACK)
    }

    private val rawTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 180, 180, 210)
        textSize = 11f * density
        typeface = Typeface.DEFAULT
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true

        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }

        // Top bar with Close button and translation count badge
        setupTopBar()

        // Fade in animation
        alphaAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener {
                currentAlpha = it.animatedValue as Float
                alpha = currentAlpha
                invalidate()
            }
            start()
        }
    }

    private fun setupTopBar() {
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * density).toInt(), (40 * density).toInt(), (16 * density).toInt(), (12 * density).toInt())
        }

        val badge = TextView(context).apply {
            text = "Parlex AR · ${boxes.size} " + if (boxes.size == 1) "block" else "blocks"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(Color.argb(200, 40, 40, 60))
                setStroke((1 * density).toInt(), Color.argb(100, 126, 120, 230))
            }
        }
        topBar.addView(badge)

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topBar.addView(spacer)

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val size = (36 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 60, 60, 80))
                setStroke((1 * density).toInt(), Color.argb(120, 160, 160, 200))
            }
            setOnClickListener {
                dismissOverlay()
            }
        }
        topBar.addView(closeBtn)

        val topParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
        }
        addView(topBar, topParams)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Draw subtle dark translucent background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. Draw translated cards over bounding boxes
        val cornerRadius = 6f * density
        val padH = 6f * density
        val padV = 4f * density

        for (box in boxes) {
            val rect = box.boundingBox
            val cardRect = RectF(
                rect.left.toFloat() - padH,
                rect.top.toFloat() - padV,
                rect.right.toFloat() + padH,
                rect.bottom.toFloat() + padV
            )

            // Draw pill background & accent border
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, boxFillPaint)
            canvas.drawRoundRect(cardRect, cornerRadius, cornerRadius, boxStrokePaint)

            // Dynamically scale text size to fit box
            val targetHeight = rect.height().toFloat()
            val computedSize = (targetHeight * 0.72f).coerceIn(12f * density, 26f * density)
            textPaint.textSize = computedSize

            // Draw translated text centered vertically in box
            val textY = rect.bottom.toFloat() - (rect.height() * 0.18f)
            canvas.drawText(box.translatedText, rect.left.toFloat(), textY, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val touchX = event.x.toInt()
            val touchY = event.y.toInt()

            // Check if user tapped inside any bounding box
            val hitBox = boxes.find { box ->
                val pad = (8 * density).toInt()
                val r = box.boundingBox
                touchX >= r.left - pad && touchX <= r.right + pad &&
                touchY >= r.top - pad && touchY <= r.bottom + pad
            }

            if (hitBox != null) {
                showBoxActionToolbar(hitBox, touchX, touchY)
                return true
            } else {
                // Tapped empty space -> dismiss action toolbar or dismiss entire overlay
                if (activeToolbar != null) {
                    dismissToolbar()
                } else {
                    dismissOverlay()
                }
                return true
            }
        }
        return true
    }

    private fun showBoxActionToolbar(box: ArTranslatedBox, touchX: Int, touchY: Int) {
        dismissToolbar()

        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(Color.argb(245, 24, 24, 38))
                setStroke((1 * density).toInt(), Color.argb(160, 126, 120, 230))
            }
            elevation = 16 * density
        }

        // Copy button
        val copyBtn = TextView(context).apply {
            text = "📋 Copy"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                clipboard.setPrimaryClip(ClipData.newPlainText("Parlex Translation", box.translatedText))
                Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                dismissToolbar()
            }
        }
        toolbar.addView(copyBtn)

        // TTS Speak button
        val speakBtn = TextView(context).apply {
            text = "🔊 Speak"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#80D8FF"))
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            setOnClickListener {
                tts?.speak(box.translatedText, TextToSpeech.QUEUE_FLUSH, null, "ar_tts_${System.currentTimeMillis()}")
            }
        }
        toolbar.addView(speakBtn)

        val tbParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = (touchX - (100 * density).toInt()).coerceIn((16 * density).toInt(), width - (220 * density).toInt())
            topMargin = (touchY - (60 * density).toInt()).coerceIn((60 * density).toInt(), height - (100 * density).toInt())
        }

        addView(toolbar, tbParams)
        activeToolbar = toolbar
    }

    private fun dismissToolbar() {
        activeToolbar?.let { removeView(it) }
        activeToolbar = null
    }

    fun dismissOverlay() {
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(alpha, 0f).apply {
            duration = 180
            addUpdateListener {
                val a = it.animatedValue as Float
                alpha = a
                if (a <= 0.05f) {
                    cleanup()
                    onDismiss()
                }
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        cleanup()
        super.onDetachedFromWindow()
    }

    private fun cleanup() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }
}
