package com.translive.app.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.speech.tts.TextToSpeech
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.translive.app.R
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * Data model for translated OCR entity.
 */
data class ArTranslatedBox(
    val rawText: String,
    val translatedText: String,
    val boundingBox: Rect,
    val sourceLangCode: String = "auto",
    val targetLangCode: String = "ru"
)

/**
 * Pre-computed render layout for zero-allocation rendering at 60/120 FPS.
 */
internal data class ArRenderBox(
    val origin: ArTranslatedBox,
    val pillRect: RectF,
    val touchRect: RectF,
    val staticLayout: StaticLayout,
    var textOffsetX: Float,
    var textOffsetY: Float,
    val backgroundColor: Int,
    val textColor: Int,
    var isShowingOriginal: Boolean = false
)

/**
 * Fullscreen In-Place AR Screen Translation Overlay.
 *
 * Implements Google Circle to Search / Apple Live Text style in-place rendering:
 * - Rounded pill backplates with adaptive high-contrast dark theme (#EE12121E)
 * - Multi-line word-wrapping via StaticLayout with dynamic auto-sizing
 * - Resilient touch handling: ambient screen taps DO NOT dismiss the overlay
 * - Floating Material 3 micro-action bar (Zero-Emoji compliance)
 * - Hardware / gesture Back key interception
 */
@SuppressLint("ViewConstructor")
/**
 * Extracts dominant edge color and determines optimal contrast text color.
 */
private object InpaintingHelper {
    fun getDominantEdgeColor(bitmap: Bitmap?, rect: Rect, padding: Int = 4): Int {
        if (bitmap == null || bitmap.isRecycled) return Color.parseColor("#EE12121E")
        val left = (rect.left - padding).coerceAtLeast(0)
        val top = (rect.top - padding).coerceAtLeast(0)
        val right = (rect.right + padding).coerceAtMost(bitmap.width)
        val bottom = (rect.bottom + padding).coerceAtMost(bitmap.height)

        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return Color.parseColor("#EE12121E")

        val colorCounts = android.util.SparseIntArray()
        var maxCount = 0
        var dominantColor = Color.parseColor("#EE12121E")

        fun processPixel(c: Int) {
            val r = Color.red(c) and 0xF8
            val g = Color.green(c) and 0xF8
            val b = Color.blue(c) and 0xF8
            val quantized = Color.rgb(r, g, b)
            val count = colorCounts.get(quantized, 0) + 1
            colorCounts.put(quantized, count)
            if (count > maxCount) {
                maxCount = count
                dominantColor = quantized
            }
        }

        val topRow = IntArray(width)
        bitmap.getPixels(topRow, 0, width, left, top, width, 1)
        for (c in topRow) processPixel(c)

        if (height > 1) {
            val bottomRow = IntArray(width)
            bitmap.getPixels(bottomRow, 0, width, left, bottom - 1, width, 1)
            for (c in bottomRow) processPixel(c)
        }

        if (height > 2) {
            val leftCol = IntArray(height - 2)
            bitmap.getPixels(leftCol, 0, 1, left, top + 1, 1, height - 2)
            for (c in leftCol) processPixel(c)

            if (width > 1) {
                val rightCol = IntArray(height - 2)
                bitmap.getPixels(rightCol, 0, 1, right - 1, top + 1, 1, height - 2)
                for (c in rightCol) processPixel(c)
            }
        }
        return dominantColor
    }

    fun getContrastTextColor(backgroundColor: Int): Int {
        val r = Color.red(backgroundColor)
        val g = Color.green(backgroundColor)
        val b = Color.blue(backgroundColor)
        val yiq = ((r * 299) + (g * 587) + (b * 114)) / 1000
        return if (yiq >= 128) Color.BLACK else Color.WHITE
    }
}

class ArTranslateOverlayView @JvmOverloads constructor(
    context: Context,
    private var rawBoxes: List<ArTranslatedBox>,
    private val initialSourceLang: String = "auto",
    private val initialTargetLang: String = "ru",
    private val frozenBitmap: Bitmap? = null,
    private val overlayStyle: String = "inpainting",
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onDismiss: () -> Unit,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density: Float = context.resources.displayMetrics.density

    // Layout constants
    private val padHorizontal = 6f * density
    private val padVertical = 4f * density
    private val minPillCornerRadius = 4f * density
    private val maxPillCornerRadius = 12f * density
    private val touchSlopPad = 8f * density

    // Paints
    private val backdropPaint = Paint().apply {
        color = Color.argb(110, 10, 10, 16) // Subtle dim background to pop translations
        style = Paint.Style.FILL
    }

    private val pillFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF13131F") // Fallback solid dark backplate
        style = Paint.Style.FILL
    }

    private val pillFillSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F51E1E2E") // Highlighted selected backplate
        style = Paint.Style.FILL
    }

    private val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT // Seamless native look (no cyan electrical tape)
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }

    private val pillStrokeSelectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF00E5FF") // High-contrast selected accent border
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
    }

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    // State
    private val renderBoxes = ArrayList<ArRenderBox>()
    private var selectedRenderBox: ArRenderBox? = null
    private var activeMicroToolbar: View? = null
    private var tts: TextToSpeech? = null
    private var isDismissing = false

    var onSaveScreenshot: (() -> Unit)? = null
    var onLanguageChanged: ((sourceLang: String, targetLang: String) -> Unit)? = null

    private var currentSourceLang: String = initialSourceLang
    private var currentTargetLang: String = initialTargetLang
    private var showOriginalText: Boolean = false

    private var activeLanguagePickerMenu: View? = null
    private var srcLangBtn: TextView? = null
    private var targetLangBtn: TextView? = null
    private var toggleOriginalBtn: TextView? = null

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true

        initTts()
        precomputeLayouts()
        resolveOverlaps()
        setupTopBar()
    }

    private fun initTts() {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    /**
     * Pre-computes StaticLayout for every translated box to ensure onDraw is zero-allocation.
     * Uses dynamic font sizing and bounds expansion to fit translated text gracefully.
     */
    private fun precomputeLayouts() {
        renderBoxes.clear()

        val minTextSizePx = 11f * density
        val maxTextSizePx = 20f * density
        val maxScreenW = screenWidth.toFloat()
        val maxScreenH = screenHeight.toFloat()

        val statusBarHeight = run {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) context.resources.getDimensionPixelSize(resId) else (38 * density).toInt()
        }
        val topMinY = statusBarHeight + (44f * density)
        val bottomMaxY = maxScreenH - (56f * density)

        for (box in rawBoxes) {
            val srcRect = box.boundingBox
            if (srcRect.width() <= 0 || srcRect.height() <= 0) continue

            val rawText = if (showOriginalText) box.rawText else box.translatedText.ifBlank { box.rawText }
            // Collapse single newlines within sentences so StaticLayout wraps naturally
            val text = rawText.replace(Regex("""(?<!\n)\n(?!\n)"""), " ").trim()
            val srcWidth = srcRect.width().toFloat()
            val srcHeight = srcRect.height().toFloat()

            // 1. Line-height estimation for multi-line blocks to prevent 22sp headline blowup
            val approxLineHeight = 20f * density
            val estimatedLineCount = max(1, (srcHeight / approxLineHeight).toInt())
            val lineHeightPx = srcHeight / estimatedLineCount
            var candidateTextSize = if (estimatedLineCount > 1) {
                (lineHeightPx * 0.74f).coerceIn(12f * density, 15f * density)
            } else {
                (srcHeight * 0.70f).coerceIn(minTextSizePx, maxTextSizePx)
            }
            textPaint.textSize = candidateTextSize

            // 2. Determine target layout width
            var targetWidth = max(srcWidth, 48f * density)
            var staticLayout = createStaticLayout(text, textPaint, targetWidth.toInt())

            // 3. Auto-fit step-down: refine font size & width if text overflows significantly
            var attempts = 0
            val maxAllowedH = if (estimatedLineCount > 1) srcHeight * 1.5f else srcHeight * 2.0f
            while (attempts < 5 && candidateTextSize > minTextSizePx && staticLayout.height > maxAllowedH) {
                candidateTextSize = max(minTextSizePx, candidateTextSize * 0.88f)
                textPaint.textSize = candidateTextSize
                targetWidth = min(maxScreenW - (24f * density), max(targetWidth, staticLayout.maxLineWidth + padHorizontal * 2))
                staticLayout = createStaticLayout(text, textPaint, targetWidth.toInt())
                attempts++
            }

            // 4. Calculate final backplate pill rectangle
            val layoutW = staticLayout.maxLineWidth
            val layoutH = staticLayout.height.toFloat()

            val finalWidth = max(srcWidth, layoutW + (padHorizontal * 2))
            val finalHeight = max(srcHeight, layoutH + (padVertical * 2))

            val centerX = srcRect.centerX().toFloat()
            val left = (centerX - finalWidth / 2f).coerceIn(8f * density, maxScreenW - finalWidth - 8f * density)

            // Top-anchoring for multi-line blocks prevents upward expansion over preceding headlines
            val isMultiLine = staticLayout.lineCount > 1 || estimatedLineCount > 1
            val top = if (isMultiLine) {
                srcRect.top.toFloat().coerceIn(topMinY, bottomMaxY - finalHeight)
            } else {
                val centerY = srcRect.centerY().toFloat()
                (centerY - finalHeight / 2f).coerceIn(topMinY, bottomMaxY - finalHeight)
            }
            val right = left + finalWidth
            val bottom = top + finalHeight

            val pillRect = RectF(left, top, right, bottom)
            val touchRect = RectF(
                left - touchSlopPad,
                top - touchSlopPad,
                right + touchSlopPad,
                bottom + touchSlopPad
            )

            val textOffsetX = left + ((finalWidth - layoutW) / 2f)
            val textOffsetY = top + ((finalHeight - layoutH) / 2f)

            val bgColor: Int
            val txtColor: Int
            if (overlayStyle == "inpainting") {
                bgColor = InpaintingHelper.getDominantEdgeColor(frozenBitmap, box.boundingBox)
                txtColor = InpaintingHelper.getContrastTextColor(bgColor)
            } else {
                bgColor = Color.parseColor("#FF13131F")
                txtColor = Color.WHITE
            }

            renderBoxes.add(
                ArRenderBox(
                    origin = box,
                    pillRect = pillRect,
                    touchRect = touchRect,
                    staticLayout = staticLayout,
                    textOffsetX = textOffsetX,
                    textOffsetY = textOffsetY,
                    backgroundColor = bgColor,
                    textColor = txtColor
                )
            )
        }
        Log.i("ArTranslateOverlayView", "Precomputed ${renderBoxes.size} render boxes")
    }

    /**
     * Anti-overlap pass: multi-pass elastic relaxation to prevent visual collisions
     * without freezing cards on top of each other.
     */
    private fun resolveOverlaps() {
        if (renderBoxes.size < 2) return
        val maxScreenH = screenHeight.toFloat()
        val statusBarHeight = run {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) context.resources.getDimensionPixelSize(resId) else (38 * density).toInt()
        }
        val topMargin = statusBarHeight + (48f * density)
        val bottomMargin = 56f * density
        val gapPx = 3f * density

        for (pass in 0 until 3) {
            val sorted = renderBoxes.sortedBy { it.pillRect.top }
            var anyShifted = false
            for (i in 0 until sorted.size - 1) {
                val upper = sorted[i].pillRect
                val lower = sorted[i + 1].pillRect
                if (RectF.intersects(upper, lower)) {
                    val overlap = upper.bottom - lower.top + gapPx
                    if (overlap > 0) {
                        val availableBelow = (maxScreenH - bottomMargin) - lower.bottom
                        if (availableBelow >= overlap) {
                            lower.offset(0f, overlap)
                            sorted[i + 1].touchRect.offset(0f, overlap)
                            sorted[i + 1].textOffsetY += overlap
                            anyShifted = true
                        } else if (availableBelow > 0) {
                            lower.offset(0f, availableBelow)
                            sorted[i + 1].touchRect.offset(0f, availableBelow)
                            sorted[i + 1].textOffsetY += availableBelow
                            val remaining = overlap - availableBelow
                            val availableAbove = upper.top - topMargin
                            if (availableAbove >= remaining) {
                                upper.offset(0f, -remaining)
                                sorted[i].touchRect.offset(0f, -remaining)
                                sorted[i].textOffsetY -= remaining
                                anyShifted = true
                            }
                        } else {
                            val availableAbove = upper.top - topMargin
                            if (availableAbove >= overlap) {
                                upper.offset(0f, -overlap)
                                sorted[i].touchRect.offset(0f, -overlap)
                                sorted[i].textOffsetY -= overlap
                                anyShifted = true
                            }
                        }
                    }
                }
            }
            if (!anyShifted) break
        }
    }

    private fun createStaticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout {
        val safeWidth = max(1, width)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text,
                paint,
                safeWidth,
                Layout.Alignment.ALIGN_NORMAL,
                1.15f,
                0f,
                false
            )
        }
    }

    private val StaticLayout.maxLineWidth: Float
        get() {
            var maxW = 0f
            for (i in 0 until lineCount) {
                val w = getLineWidth(i)
                if (w > maxW) maxW = w
            }
            return maxW
        }

    // --- Top Bar & Bottom Dismiss Pill UI (Strict MD3 Zero-Emoji) ---

    private fun setupTopBar() {
        val statusBarHeight = run {
            val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (resId > 0) context.resources.getDimensionPixelSize(resId) else (38 * density).toInt()
        }
        val topBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((12 * density).toInt(), statusBarHeight + (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
        }

        // 1. Interactive Language Pill Container (Circle to Search / Apple Glass Style)
        val langContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18 * density
                setColor(Color.parseColor("#E61E1E28"))
                setStroke((1 * density).toInt(), Color.parseColor("#33FFFFFF"))
            }
        }

        val srcBtn = TextView(context).apply {
            text = formatLangLabel(currentSourceLang)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#80D8FF"))
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                showLanguagePicker(isSource = true, anchor = this)
            }
        }
        srcLangBtn = srcBtn
        langContainer.addView(srcBtn)

        val swapBtn = TextView(context).apply {
            text = "⇄"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val swapSize = (26 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(swapSize, swapSize)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFFFFF"))
            }
            setOnClickListener {
                animate().rotationBy(180f).setDuration(200L).start()
                performSwapLanguages()
            }
        }
        langContainer.addView(swapBtn)

        val tgtBtn = TextView(context).apply {
            text = formatLangLabel(currentTargetLang)
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.parseColor("#80D8FF"))
            gravity = Gravity.CENTER
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            setOnClickListener {
                showLanguagePicker(isSource = false, anchor = this)
            }
        }
        targetLangBtn = tgtBtn
        langContainer.addView(tgtBtn)

        topBar.addView(langContainer)

        // 2. Global Original / Translation Toggle Button
        val toggleBtn = TextView(context).apply {
            text = if (showOriginalText) "Original" else "Translate"
            textSize = 11f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (32 * density).toInt()
            ).apply {
                marginStart = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(if (showOriginalText) Color.parseColor("#CCF59E0B") else Color.parseColor("#CC2A2A38"))
                setStroke((1 * density).toInt(), Color.parseColor("#44FFFFFF"))
            }
            setOnClickListener {
                showOriginalText = !showOriginalText
                text = if (showOriginalText) "Original" else "Translate"
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16 * density
                    setColor(if (showOriginalText) Color.parseColor("#CCF59E0B") else Color.parseColor("#CC2A2A38"))
                    setStroke((1 * density).toInt(), Color.parseColor("#44FFFFFF"))
                }
                precomputeLayouts()
                resolveOverlaps()
                invalidate()
            }
        }
        toggleOriginalBtn = toggleBtn
        topBar.addView(toggleBtn)

        val spacer = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        }
        topBar.addView(spacer)

        val saveBtn = TextView(context).apply {
            text = "Save"
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (36 * density).toInt()
            ).apply {
                marginEnd = (8 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 16 * density
                setColor(Color.parseColor("#CC2A2A38"))
                setStroke((1 * density).toInt(), Color.parseColor("#44FFFFFF"))
            }
            setOnClickListener { onSaveScreenshot?.invoke() }
        }
        topBar.addView(saveBtn)

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val size = (36 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#CC2A2A38"))
                setStroke((1 * density).toInt(), Color.parseColor("#44FFFFFF"))
            }
            setOnClickListener { dismissOverlay() }
        }
        topBar.addView(closeBtn)

        val topParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
        }
        addView(topBar, topParams)
    }

    private fun showLanguagePicker(isSource: Boolean, anchor: View) {
        dismissLanguagePicker()
        val languages = if (isSource) {
            listOf("auto" to "Auto") + listOf(
                "en" to "English", "ru" to "Russian", "zh" to "Chinese",
                "de" to "German", "fr" to "French", "es" to "Spanish",
                "ja" to "Japanese", "ko" to "Korean", "vi" to "Vietnamese"
            )
        } else {
            listOf(
                "ru" to "Russian", "en" to "English", "zh" to "Chinese",
                "de" to "German", "fr" to "French", "es" to "Spanish",
                "ja" to "Japanese", "ko" to "Korean", "vi" to "Vietnamese"
            )
        }

        val pickerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(Color.parseColor("#F5161622"))
                setStroke((1 * density).toInt(), Color.parseColor("#4D80D8FF"))
            }
            elevation = 20 * density
        }

        for ((code, name) in languages) {
            val item = TextView(context).apply {
                text = "$name (${code.uppercase()})"
                textSize = 12f
                typeface = Typeface.DEFAULT
                setTextColor(Color.WHITE)
                setPadding((12 * density).toInt(), (7 * density).toInt(), (12 * density).toInt(), (7 * density).toInt())
                setOnClickListener {
                    if (isSource) {
                        currentSourceLang = code
                    } else {
                        currentTargetLang = code
                    }
                    updateTopBarLabels()
                    dismissLanguagePicker()
                    onLanguageChanged?.invoke(currentSourceLang, currentTargetLang)
                }
            }
            pickerLayout.addView(item)
        }

        val anchorLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        val pickerWidth = (140 * density).toInt()
        val posX = anchorLoc[0].coerceIn((8 * density).toInt(), screenWidth - pickerWidth - (8 * density).toInt())
        val posY = (anchorLoc[1] + anchor.height + (4 * density).toInt())

        val params = LayoutParams(pickerWidth, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = posX
            topMargin = posY
        }

        addView(pickerLayout, params)
        activeLanguagePickerMenu = pickerLayout
    }

    private fun dismissLanguagePicker() {
        activeLanguagePickerMenu?.let {
            removeView(it)
        }
        activeLanguagePickerMenu = null
    }

    private fun performSwapLanguages() {
        val detectedSrc = rawBoxes.firstOrNull()?.sourceLangCode ?: "en"
        val oldSource = if (currentSourceLang == "auto") detectedSrc else currentSourceLang
        val oldTarget = currentTargetLang

        currentSourceLang = oldTarget
        currentTargetLang = oldSource

        updateTopBarLabels()
        onLanguageChanged?.invoke(currentSourceLang, currentTargetLang)
    }

    private fun updateTopBarLabels() {
        srcLangBtn?.text = formatLangLabel(currentSourceLang)
        targetLangBtn?.text = formatLangLabel(currentTargetLang)
    }

    private fun formatLangLabel(code: String): String = when (code.lowercase()) {
        "auto" -> "Auto ▼"
        "ru" -> "RU ▼"
        "en" -> "EN ▼"
        "zh" -> "ZH ▼"
        "de" -> "DE ▼"
        "fr" -> "FR ▼"
        "es" -> "ES ▼"
        "ja" -> "JA ▼"
        "ko" -> "KO ▼"
        "vi" -> "VI ▼"
        else -> "${code.uppercase()} ▼"
    }

    fun updateBoxes(newBoxes: List<ArTranslatedBox>, sourceCode: String, targetCode: String) {
        this.rawBoxes = newBoxes
        this.currentSourceLang = sourceCode
        this.currentTargetLang = targetCode
        updateTopBarLabels()

        animate().alpha(0.5f).setDuration(70L).withEndAction {
            precomputeLayouts()
            resolveOverlaps()
            invalidate()
            animate().alpha(1.0f).setDuration(120L).start()
        }.start()
    }

    // --- Drawing Engine ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 1. Frozen Screenshot Background (if available)
        frozenBitmap?.let { bmp ->
            if (!bmp.isRecycled) {
                canvas.drawBitmap(bmp, 0f, 0f, null)
            }
        }

        // 2. Dim Backdrop
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdropPaint)

        // 2. Draw In-Place Translated Pills
        for (item in renderBoxes) {
            val isSelected = (item == selectedRenderBox)
            val rect = item.pillRect
            val cornerRadius = (rect.height() / 2f).coerceIn(minPillCornerRadius, maxPillCornerRadius)

            // Draw pill fill (use dynamic inpainting color if not selected)
            if (isSelected) {
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, pillFillSelectedPaint)
            } else {
                pillFillPaint.color = item.backgroundColor
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, pillFillPaint)
            }

            // Draw pill stroke border
            val strokePaint = if (isSelected) pillStrokeSelectedPaint else pillStrokePaint
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)

            // Draw translated text layout
            canvas.save()
            canvas.translate(item.textOffsetX, item.textOffsetY)
            item.staticLayout.paint.color = item.textColor
            item.staticLayout.draw(canvas)
            canvas.restore()
        }
    }

    // --- Resilient Touch & Gesture Handling ---

    // Edge-swipe fallback gesture state
    private val edgeMarginPx = 28f * density
    private val minSwipeDistPx = 64f * density
    private var isEdgeSwipe = false
    private var swipeStartX = 0f
    private var swipeStartY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = event.rawX
                swipeStartY = event.rawY
                isEdgeSwipe = (swipeStartX <= edgeMarginPx || swipeStartX >= width - edgeMarginPx)
            }
            MotionEvent.ACTION_UP -> {
                // Edge-swipe dismiss fallback (for OEMs where system back gesture doesn't route to overlays)
                if (isEdgeSwipe) {
                    val dx = event.rawX - swipeStartX
                    val dy = event.rawY - swipeStartY
                    val isLeftEdgeSwipe = swipeStartX <= edgeMarginPx && dx > minSwipeDistPx
                    val isRightEdgeSwipe = swipeStartX >= width - edgeMarginPx && -dx > minSwipeDistPx
                    val isHorizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                    if ((isLeftEdgeSwipe || isRightEdgeSwipe) && isHorizontal) {
                        handleBackAction()
                        return true
                    }
                }

                val touchX = event.x
                val touchY = event.y

                // 1. Check if user tapped inside any translated pill
                val hitItem = renderBoxes.find { it.touchRect.contains(touchX, touchY) }

                if (hitItem != null) {
                    selectedRenderBox = hitItem
                    showMicroActionBar(hitItem)
                    invalidate()
                    return true
                }

                // 2. Ambient tap on empty space -> Dismiss language picker / toolbar / deselect box if active, otherwise dismiss overlay
                if (activeLanguagePickerMenu != null) {
                    dismissLanguagePicker()
                    return true
                }
                if (activeMicroToolbar != null || selectedRenderBox != null) {
                    dismissMicroToolbar()
                    selectedRenderBox = null
                    invalidate()
                    return true
                } else {
                    dismissOverlay()
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    // --- Floating Material 3 Micro-Action Bar (Zero-Emoji Compliance) ---

    private fun showMicroActionBar(item: ArRenderBox) {
        dismissMicroToolbar()

        val toolbar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(Color.parseColor("#F5181824"))
                setStroke((1 * density).toInt(), Color.parseColor("#4D80D8FF"))
            }
        }

        // Action 1: Context-Aware Copy (copies active text: original or translated)
        toolbar.addView(createMicroActionButton("Copy") {
            val textToCopy = if (item.isShowingOriginal) item.origin.rawText else item.origin.translatedText
            val clip = context.getSystemService(ClipboardManager::class.java)
            clip?.setPrimaryClip(ClipData.newPlainText("Parlex", textToCopy))
            Toast.makeText(context, if (item.isShowingOriginal) "Copied original" else "Copied translation", Toast.LENGTH_SHORT).show()
            dismissMicroToolbar()
        })

        // Action 2: Speak TTS
        toolbar.addView(createMicroActionButton("Speak") {
            val textToSpeak = if (item.isShowingOriginal) item.origin.rawText else item.origin.translatedText
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "ar_speak_${System.currentTimeMillis()}")
        })

        // Action 3: Toggle Original / Translation for this specific card
        val toggleLabel = if (item.isShowingOriginal) "Translate" else "Original"
        toolbar.addView(createMicroActionButton(toggleLabel) {
            item.isShowingOriginal = !item.isShowingOriginal
            val displayText = if (item.isShowingOriginal) item.origin.rawText else item.origin.translatedText
            textPaint.textSize = item.staticLayout.paint.textSize
            val newLayout = createStaticLayout(displayText, textPaint, item.pillRect.width().toInt())
            val idx = renderBoxes.indexOf(item)
            if (idx >= 0) {
                renderBoxes[idx] = item.copy(staticLayout = newLayout, isShowingOriginal = item.isShowingOriginal)
            }
            invalidate()
            dismissMicroToolbar()
        })

        // Action 4: Hide Individual Pill
        toolbar.addView(createMicroActionButton("Hide") {
            renderBoxes.remove(item)
            selectedRenderBox = null
            invalidate()
            dismissMicroToolbar()
        })

        val scrollContainer = android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            elevation = 16 * density
            addView(toolbar, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }

        // Anchor positioning: Above pill by default; below if too close to top
        val tbWidthEstimate = (220 * density).toInt()
        val tbHeightEstimate = (44 * density).toInt()
        val maxAllowedWidth = screenWidth - (24 * density).toInt()
        val effectiveWidth = min(tbWidthEstimate, maxAllowedWidth)

        val pill = item.pillRect
        val posX = (pill.centerX() - effectiveWidth / 2f).toInt()
            .coerceIn((12 * density).toInt(), screenWidth - effectiveWidth - (12 * density).toInt())

        var posY = (pill.top - tbHeightEstimate - (8 * density)).toInt()
        if (posY < (60 * density).toInt()) {
            posY = (pill.bottom + (8 * density)).toInt()
        }

        val params = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = posX
            topMargin = posY
        }

        addView(scrollContainer, params)
        activeMicroToolbar = scrollContainer

        scrollContainer.scaleX = 0.85f
        scrollContainer.scaleY = 0.85f
        scrollContainer.alpha = 0f
        scrollContainer.animate()
            .scaleX(1f)
            .scaleY(1f)
            .alpha(1f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun createMicroActionButton(label: String, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            text = label
            textSize = 12f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8 * density
                setColor(Color.parseColor("#26FFFFFF"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins((3 * density).toInt(), 0, (3 * density).toInt(), 0)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dismissMicroToolbar() {
        activeMicroToolbar?.let { tb ->
            removeView(tb)
        }
        activeMicroToolbar = null
    }

    // --- Back Key Interception ---

    private var backCallback: Any? = null // OnBackInvokedCallback (API 33+)

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val dispatcher = findOnBackInvokedDispatcher()
                if (dispatcher != null) {
                    val callback = android.window.OnBackInvokedCallback { handleBackAction() }
                    dispatcher.registerOnBackInvokedCallback(
                        android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                        callback
                    )
                    backCallback = callback
                }
            } catch (_: Exception) { /* API not available on this device */ }
        }
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            try {
                val dispatcher = findOnBackInvokedDispatcher()
                dispatcher?.unregisterOnBackInvokedCallback(
                    backCallback as android.window.OnBackInvokedCallback
                )
            } catch (_: Exception) { }
            backCallback = null
        }
        cleanup()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                return true // Must consume ACTION_DOWN to claim key sequence on all OEMs
            }
            if (event.action == KeyEvent.ACTION_UP) {
                handleBackAction()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleBackAction() {
        if (activeLanguagePickerMenu != null) {
            dismissLanguagePicker()
            return
        }
        if (activeMicroToolbar != null || selectedRenderBox != null) {
            dismissMicroToolbar()
            selectedRenderBox = null
            invalidate()
        } else {
            dismissOverlay()
        }
    }

    fun dismissOverlay() {
        if (isDismissing) return
        isDismissing = true
        animate()
            .alpha(0f)
            .setDuration(160L)
            .withEndAction {
                cleanup()
                onDismiss()
            }
            .start()
    }


    private fun cleanup() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }
}
