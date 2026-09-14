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
 * Line-level pill boundary for per-line inpainting and masking.
 */
data class ArLinePill(
    val lineRect: RectF,
    val lineIndex: Int
)

/**
 * Pre-computed render layout for zero-allocation rendering at 60/120 FPS.
 */
internal data class ArRenderBox(
    val origin: ArTranslatedBox,
    val pillRect: RectF,
    val touchRect: RectF,
    val eraseRect: RectF,
    val linePills: List<ArLinePill>,
    val staticLayout: StaticLayout,
    var textOffsetX: Float,
    var textOffsetY: Float,
    val backgroundColor: Int,
    val textColor: Int,
    var isShowingOriginal: Boolean = false
) {
    fun offset(dx: Float, dy: Float) {
        pillRect.offset(dx, dy)
        touchRect.offset(dx, dy)
        // eraseRect remains strictly pinned to original screen bounds on frozenBitmap (zero-drift inpainting)
        textOffsetX += dx
        textOffsetY += dy
        for (lp in linePills) {
            lp.lineRect.offset(dx, dy)
        }
    }
}

/**
 * Fullscreen In-Place AR Screen Translation Overlay.
 *
 * Implements Google Circle to Search / Apple Live Text style in-place rendering:
 * - Direct Skia Canvas inpainting (Zero-Card Direct Erase)
 * - Strict per-line left-edge anchoring to original source margin
 * - Multi-line word-wrapping via StaticLayout with dynamic auto-sizing
 * - Column-aware downward elastic collision avoidance
 * - Resilient touch handling: ambient screen taps DO NOT dismiss the overlay
 * - Floating Material 3 micro-action bar (Zero-Emoji compliance)
 * - Hardware / gesture Back key interception
 */
private inline fun Float.safeCoerceIn(minVal: Float, maxVal: Float): Float =
    this.coerceIn(minVal, max(minVal, maxVal))

private inline fun Int.safeCoerceIn(minVal: Int, maxVal: Int): Int =
    this.coerceIn(minVal, max(minVal, maxVal))

/**
 * Extracts dominant edge color via 48-point perimeter sampling with 25% trimmed-mean outlier rejection.
 */
private object InpaintingHelper {
    private const val FALLBACK_SURFACE_COLOR = 0xF01E1E1E.toInt()

    fun getDominantEdgeColor(bitmap: Bitmap?, rect: Rect, padding: Int = 4): Int {
        if (bitmap == null || bitmap.isRecycled) return FALLBACK_SURFACE_COLOR
        val left = (rect.left - padding).coerceIn(0, bitmap.width - 1)
        val top = (rect.top - padding).coerceIn(0, bitmap.height - 1)
        val right = (rect.right + padding).coerceIn(0, bitmap.width - 1)
        val bottom = (rect.bottom + padding).coerceIn(0, bitmap.height - 1)

        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return FALLBACK_SURFACE_COLOR

        val samples = ArrayList<Int>(48)

        // 16 points along top edge
        for (i in 0 until 16) {
            val x = left + (width * i / 15)
            samples.add(bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), top))
        }

        // 16 points along bottom edge
        for (i in 0 until 16) {
            val x = left + (width * i / 15)
            samples.add(bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), bottom))
        }

        // 8 points along left edge (interior)
        for (i in 1..8) {
            val y = top + (height * i / 9)
            samples.add(bitmap.getPixel(left, y.coerceIn(0, bitmap.height - 1)))
        }

        // 8 points along right edge (interior)
        for (i in 1..8) {
            val y = top + (height * i / 9)
            samples.add(bitmap.getPixel(right, y.coerceIn(0, bitmap.height - 1)))
        }

        if (samples.isEmpty()) return FALLBACK_SURFACE_COLOR

        // 25% trimmed-mean outlier rejection based on Rec. 601 luminance
        fun luma(c: Int): Float {
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            return 0.299f * r + 0.587f * g + 0.114f * b
        }

        samples.sortBy { luma(it) }
        val trimCount = (samples.size * 0.25f).toInt().coerceAtMost(samples.size / 3)
        val trimmed = if (trimCount > 0 && samples.size > trimCount * 2) {
            samples.subList(trimCount, samples.size - trimCount)
        } else {
            samples
        }

        var rSum = 0L
        var gSum = 0L
        var bSum = 0L
        for (c in trimmed) {
            rSum += Color.red(c)
            gSum += Color.green(c)
            bSum += Color.blue(c)
        }
        val count = trimmed.size.coerceAtLeast(1)
        return Color.rgb((rSum / count).toInt(), (gSum / count).toInt(), (bSum / count).toInt())
    }

    fun isDark(backgroundColor: Int): Boolean {
        val r = Color.red(backgroundColor)
        val g = Color.green(backgroundColor)
        val b = Color.blue(backgroundColor)
        val yiq = ((r * 299) + (g * 587) + (b * 114)) / 1000
        return yiq < 128
    }

    fun getContrastTextColor(backgroundColor: Int): Int {
        return if (isDark(backgroundColor)) Color.WHITE else Color.BLACK
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

    private val erasePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
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
     * Uses per-line left-edge anchoring (Approach 2) matching Google Lens / Circle to Search.
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

            // 1. Typography role & proportional font sizing
            val approxLineHeight = 18f * density
            val estimatedLineCount = max(1, (srcHeight / approxLineHeight).toInt())
            val origLineHeight = srcHeight / estimatedLineCount

            val isHeadline = (srcHeight >= 20f * density) ||
                (estimatedLineCount <= 2 && (box.rawText.length < 80 && !box.rawText.endsWith(".")))

            var candidateTextSize = if (isHeadline) {
                (srcHeight * 0.74f).safeCoerceIn(13f * density, 19f * density)
            } else if (estimatedLineCount > 1) {
                (origLineHeight * 0.72f).safeCoerceIn(11f * density, 15f * density)
            } else {
                (srcHeight * 0.70f).safeCoerceIn(minTextSizePx, max(minTextSizePx, maxTextSizePx))
            }

            val bgColor: Int
            val txtColor: Int
            if (overlayStyle == "inpainting") {
                bgColor = InpaintingHelper.getDominantEdgeColor(frozenBitmap, box.boundingBox, (4 * density).toInt())
                val isDarkBg = InpaintingHelper.isDark(bgColor)
                txtColor = if (isHeadline) {
                    if (isDarkBg) Color.WHITE else Color.parseColor("#FF1A1A1A")
                } else {
                    if (isDarkBg) Color.parseColor("#FFE0E0E6") else Color.parseColor("#FF424242")
                }
            } else {
                bgColor = Color.parseColor("#FF13131F")
                txtColor = if (isHeadline) Color.WHITE else Color.parseColor("#FFE0E0E6")
            }

            // Dedicated TextPaint per box prevents shared state corruption across different sizes/typefaces
            val boxPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = candidateTextSize
                this.typeface = if (isHeadline) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                this.color = txtColor
            }

            // 2. Determine target layout width anchored to srcRect.left
            val maxAvailableW = max(48f * density, maxScreenW - srcRect.left.toFloat() - (16f * density))
            var targetWidth = min(maxAvailableW, max(srcWidth, 48f * density))
            var staticLayout = createStaticLayout(text, boxPaint, targetWidth.toInt())

            // 3. Auto-fit step-down: refine font size if height blows up
            var attempts = 0
            val maxAllowedH = if (estimatedLineCount > 1) srcHeight * 1.5f else srcHeight * 2.0f
            while (attempts < 4 && candidateTextSize > minTextSizePx && staticLayout.height > maxAllowedH) {
                candidateTextSize = max(minTextSizePx, candidateTextSize * 0.90f)
                boxPaint.textSize = candidateTextSize
                targetWidth = min(maxAvailableW, max(targetWidth, staticLayout.maxLineWidth + padHorizontal * 2))
                staticLayout = createStaticLayout(text, boxPaint, targetWidth.toInt())
                attempts++
            }

            // 4. Strict Left-Edge Anchoring (No horizontal centering!)
            val layoutW = staticLayout.maxLineWidth
            val layoutH = staticLayout.height.toFloat()

            val minLeft = 8f * density
            val maxLeft = max(minLeft, maxScreenW - layoutW - minLeft)
            val textOffsetX = srcRect.left.toFloat().safeCoerceIn(minLeft, maxLeft)

            val maxTop = max(topMinY, bottomMaxY - layoutH)
            val textOffsetY = srcRect.top.toFloat().safeCoerceIn(topMinY, maxTop)

            // 5. Erase rectangle covering original bounding box with 3dp/2dp bleed
            val bleedX = 3f * density
            val bleedY = 2f * density
            val eraseRect = RectF(
                (srcRect.left - bleedX).coerceAtLeast(0f),
                (srcRect.top - bleedY).coerceAtLeast(0f),
                (srcRect.right + bleedX).coerceAtMost(maxScreenW),
                (srcRect.bottom + bleedY).coerceAtMost(maxScreenH)
            )

            // 6. Per-line pill decomposition
            val linePills = ArrayList<ArLinePill>(staticLayout.lineCount)
            val linePadH = 4f * density
            val linePadV = 2f * density
            for (i in 0 until staticLayout.lineCount) {
                val lLeft = textOffsetX + staticLayout.getLineLeft(i)
                val lWidth = staticLayout.getLineWidth(i)
                val lTop = textOffsetY + staticLayout.getLineTop(i)
                val lBottom = textOffsetY + staticLayout.getLineBottom(i)
                val pillRect = RectF(
                    lLeft - linePadH,
                    lTop - linePadV,
                    lLeft + lWidth + linePadH,
                    lBottom + linePadV
                )
                linePills.add(ArLinePill(pillRect, i))
            }

            // 7. Overall pillRect (union of eraseRect and layout) and touchRect
            val pillLeft = min(eraseRect.left, textOffsetX - linePadH)
            val pillTop = min(eraseRect.top, textOffsetY - linePadV)
            val pillRight = max(eraseRect.right, textOffsetX + layoutW + linePadH)
            val pillBottom = max(eraseRect.bottom, textOffsetY + layoutH + linePadV)
            val pillRect = RectF(pillLeft, pillTop, pillRight, pillBottom)

            val touchRect = RectF(
                pillRect.left - touchSlopPad,
                pillRect.top - touchSlopPad,
                pillRect.right + touchSlopPad,
                pillRect.bottom + touchSlopPad
            )

            renderBoxes.add(
                ArRenderBox(
                    origin = box,
                    pillRect = pillRect,
                    touchRect = touchRect,
                    eraseRect = eraseRect,
                    linePills = linePills,
                    staticLayout = staticLayout,
                    textOffsetX = textOffsetX,
                    textOffsetY = textOffsetY,
                    backgroundColor = bgColor,
                    textColor = txtColor
                )
            )
        }
        Log.i("ArTranslateOverlayView", "Precomputed ${renderBoxes.size} render boxes (Approach 2)")
    }

    private fun calculateHorizontalOverlapRatio(a: RectF, b: RectF): Float {
        val overlapLeft = max(a.left, b.left)
        val overlapRight = min(a.right, b.right)
        val overlapWidth = max(0f, overlapRight - overlapLeft)
        val minWidth = min(a.width(), b.width())
        if (minWidth <= 0f) return 0f
        return overlapWidth / minWidth
    }

    /**
     * Column-aware downward elastic collision avoidance engine.
     * Monotonic forward-scan pass ensures all items sharing horizontal overlap (>= 20%)
     * never collide, even across interleaved multi-column hierarchies.
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
        var gapPx = 4f * density
        val minColOverlap = 0.20f

        for (pass in 0 until 4) {
            val sorted = renderBoxes.sortedBy { it.pillRect.top }
            var anyShifted = false

            for (i in 0 until sorted.size) {
                val current = sorted[i]
                var highestRequiredTop = current.pillRect.top
                for (j in 0 until i) {
                    val prev = sorted[j]
                    val colOverlap = calculateHorizontalOverlapRatio(prev.pillRect, current.pillRect)
                    if (colOverlap >= minColOverlap) {
                        val neededTop = prev.pillRect.bottom + gapPx
                        if (neededTop > highestRequiredTop) {
                            highestRequiredTop = neededTop
                        }
                    }
                }

                val pushDown = highestRequiredTop - current.pillRect.top
                if (pushDown > 0.5f) {
                    current.offset(0f, pushDown)
                    anyShifted = true
                }
            }

            // Upward screen containment preserving relative spacing
            val lowestBottom = sorted.maxOfOrNull { it.pillRect.bottom } ?: 0f
            val screenLimit = maxScreenH - bottomMargin
            if (lowestBottom > screenLimit) {
                val overflow = lowestBottom - screenLimit
                val minTop = sorted.minOfOrNull { it.pillRect.top } ?: 0f
                val availableHeadroom = max(0f, minTop - topMargin)
                val upwardShift = min(overflow, availableHeadroom)
                if (upwardShift > 0.5f) {
                    for (box in sorted) {
                        box.offset(0f, -upwardShift)
                    }
                    anyShifted = true
                }

                if (gapPx > 2f * density) {
                    gapPx = 2f * density
                    anyShifted = true
                }

                // If still overflowing after upward shift and gap compression:
                val remainingOverflow = (sorted.maxOfOrNull { it.pillRect.bottom } ?: 0f) - screenLimit
                if (remainingOverflow > 0.5f) {
                    var nextTop = screenLimit
                    for (k in sorted.indices.reversed()) {
                        val item = sorted[k]
                        if (item.pillRect.bottom > nextTop) {
                            val pullUp = item.pillRect.bottom - nextTop
                            item.offset(0f, -pullUp)
                            anyShifted = true
                        }
                        nextTop = item.pillRect.top - gapPx
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
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
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
        val minPickerX = (8 * density).toInt()
        val maxPickerX = max(minPickerX, screenWidth - pickerWidth - minPickerX)
        val posX = anchorLoc[0].safeCoerceIn(minPickerX, maxPickerX)
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

        // 2. Dim Backdrop (Only if not using direct inpainting; direct inpainting is completely seamless)
        if (overlayStyle != "inpainting") {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backdropPaint)
        }

        val cornerRadius = 3f * density

        // 3. Batched Inpainting Erase Pass (Direct Canvas Masking - Zero Dark Halos)
        if (!showOriginalText) {
            for (item in renderBoxes) {
                if (item.isShowingOriginal) continue
                erasePaint.color = item.backgroundColor

                // Erase the original text bounds (anchored strictly to original coordinates)
                canvas.drawRoundRect(item.eraseRect, cornerRadius, cornerRadius, erasePaint)

                // Mask per-line expansion bounds
                for (lp in item.linePills) {
                    canvas.drawRoundRect(lp.lineRect, cornerRadius, cornerRadius, erasePaint)
                }
            }
        }

        // 4. Batched Text Layout Pass (Zero-allocation Skia StaticLayout with dedicated per-box typography)
        if (!showOriginalText) {
            for (item in renderBoxes) {
                if (item.isShowingOriginal) continue
                canvas.save()
                canvas.translate(item.textOffsetX, item.textOffsetY)
                item.staticLayout.draw(canvas)
                canvas.restore()
            }
        }

        // 5. Interactive Selection Highlight (Only on tapped box, no wireframes on unselected boxes!)
        selectedRenderBox?.let { selected ->
            val rect = selected.pillRect
            canvas.drawRoundRect(rect, 6f * density, 6f * density, pillStrokeSelectedPaint)
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
        val minBarX = (12 * density).toInt()
        val maxBarX = max(minBarX, screenWidth - effectiveWidth - minBarX)
        val posX = (pill.centerX() - effectiveWidth / 2f).toInt().safeCoerceIn(minBarX, maxBarX)

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

    fun dismissOverlay(immediate: Boolean = false) {
        if (isDismissing) return
        isDismissing = true
        animate().cancel()
        if (immediate) {
            cleanup()
            onDismiss()
        } else {
            animate()
                .alpha(0f)
                .setDuration(140L)
                .withEndAction {
                    cleanup()
                    onDismiss()
                }
                .start()
        }
    }


    private fun cleanup() {
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }
}
