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
import com.translive.app.data.model.Language
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
    val textOffsetX: Float,
    val textOffsetY: Float,
    val backgroundColor: Int,
    val textColor: Int,
    val backgroundPatch: Bitmap? = null,
    val isTruncated: Boolean = false,
    var isShowingOriginal: Boolean = false,
    var revealDelay: Float = 0f
)

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

    /**
     * Builds a tiny blurred patch from the original region. Drawn stretched over
     * the source text bounds it preserves gradients and photo backgrounds far
     * better than a single flat sampled colour, while staying cheap enough to run
     * on the UI thread during overlay creation.
     */
    fun createBackgroundPatch(bitmap: Bitmap?, rect: Rect, patchSize: Int = 18): Bitmap? {
        if (bitmap == null || bitmap.isRecycled) return null
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        if (right <= left || bottom <= top) return null

        val pixels = IntArray(patchSize * patchSize)
        val width = right - left
        val height = bottom - top
        for (y in 0 until patchSize) {
            val sy = top + (height * y / patchSize).coerceIn(0, height - 1)
            for (x in 0 until patchSize) {
                val sx = left + (width * x / patchSize).coerceIn(0, width - 1)
                pixels[y * patchSize + x] = bitmap.getPixel(sx, sy)
            }
        }

        // Two 3x3 box-blur passes remove residual glyph edges.
        repeat(2) {
            val copy = pixels.copyOf()
            for (y in 0 until patchSize) {
                for (x in 0 until patchSize) {
                    var r = 0
                    var g = 0
                    var b = 0
                    var count = 0
                    for (dy in -1..1) {
                        val yy = (y + dy).coerceIn(0, patchSize - 1)
                        for (dx in -1..1) {
                            val xx = (x + dx).coerceIn(0, patchSize - 1)
                            val c = copy[yy * patchSize + xx]
                            r += Color.red(c)
                            g += Color.green(c)
                            b += Color.blue(c)
                            count++
                        }
                    }
                    pixels[y * patchSize + x] = Color.rgb(r / count, g / count, b / count)
                }
            }
        }
        return Bitmap.createBitmap(pixels, patchSize, patchSize, Bitmap.Config.ARGB_8888)
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

    private val patchPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private val screenshotPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

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
        renderBoxes.forEach { it.backgroundPatch?.recycle() }
        renderBoxes.clear()

        val scaledDensity = density * context.resources.configuration.fontScale
        val minTextSizePx = 9f * scaledDensity
        val maxScreenW = screenWidth.toFloat()
        val maxScreenH = screenHeight.toFloat()

        for (box in rawBoxes) {
            val srcRect = box.boundingBox
            if (srcRect.width() <= 0 || srcRect.height() <= 0) continue

            val rawText = if (showOriginalText) box.rawText else box.translatedText.ifBlank { box.rawText }
            val text = rawText.replace(Regex("""(?<!\n)\n(?!\n)"""), " ").trim()
            if (text.isBlank()) continue
            val srcWidth = srcRect.width().toFloat()
            val srcHeight = srcRect.height().toFloat()

            // 1. Typography role & proportional font sizing
            val approxLineHeight = 16f * scaledDensity
            val estimatedLineCount = max(1, (srcHeight / approxLineHeight).toInt())
            val isHeadline = (srcHeight >= 20f * density) ||
                (estimatedLineCount <= 2 && box.rawText.length < 80 && !box.rawText.endsWith("."))

            // The translation must live inside the original block. Start from a
            // font size that fits the source line height, never from a size that
            // forces the text to grow over neighbouring content.
            var candidateTextSize = ArTextFit.initialTextSizePx(
                sourceHeightPx = srcHeight,
                estimatedLines = estimatedLineCount,
                minTextSizePx = 9f * scaledDensity,
                maxTextSizePx = 22f * scaledDensity
            )

            val bgColor: Int
            val txtColor: Int
            if (overlayStyle == "inpainting") {
                bgColor = InpaintingHelper.getDominantEdgeColor(frozenBitmap, srcRect, (4 * density).toInt())
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

            val boxPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = candidateTextSize
                this.typeface = if (isHeadline) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                this.color = txtColor
            }

            // 2. Fixed width = original block width, minus inside padding. Never
            // expand horizontally into a neighbouring column.
            val targetWidth = max(24f * density, srcWidth - padHorizontal * 2)
            var staticLayout = createStaticLayout(text, boxPaint, targetWidth.toInt())

            // 3. Fit vertically inside the source block. If the translation is
            // longer than the original, shrink first and ellipsize afterwards.
            var attempts = 0
            while (attempts < 12 && candidateTextSize > minTextSizePx && staticLayout.height > srcHeight) {
                candidateTextSize = ArTextFit.nextSmallerSize(candidateTextSize, minTextSizePx)
                boxPaint.textSize = candidateTextSize
                staticLayout = createStaticLayout(text, boxPaint, targetWidth.toInt())
                attempts++
            }

            val maxLines = ArTextFit.maxLinesFor(srcHeight, boxPaint.fontSpacing)
            val isTruncated = ArTextFit.needsTruncation(staticLayout.lineCount, maxLines)
            if (isTruncated) {
                staticLayout = createTruncatedLayout(text, boxPaint, targetWidth.toInt(), maxLines)
            }

            // 4. Anchor the text inside the original block (no screen-level drift).
            val layoutW = min(staticLayout.maxLineWidth, targetWidth)
            val layoutH = staticLayout.height.toFloat()
            val textOffsetX = (srcRect.left + padHorizontal)
                .safeCoerceIn(0f, max(0f, maxScreenW - layoutW))
            val textOffsetY = (srcRect.top + max(0f, (srcHeight - layoutH) / 2f))
                .safeCoerceIn(0f, max(0f, maxScreenH - layoutH))

            // 5. Erase rectangle covering only the original text bounds.
            val bleedX = 3f * density
            val bleedY = 2f * density
            val eraseRect = RectF(
                (srcRect.left - bleedX).coerceAtLeast(0f),
                (srcRect.top - bleedY).coerceAtLeast(0f),
                (srcRect.right + bleedX).coerceAtMost(maxScreenW),
                (srcRect.bottom + bleedY).coerceAtMost(maxScreenH)
            )

            val linePadH = 2f * density
            val linePadV = 1f * density
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

            val backgroundPatch = if (overlayStyle == "inpainting") {
                InpaintingHelper.createBackgroundPatch(frozenBitmap, srcRect)
            } else null

            renderBoxes.add(
                ArRenderBox(
                    origin = box,
                    pillRect = pillRect,
                    touchRect = touchRect,
                    eraseRect = eraseRect,
                    linePills = emptyList(),
                    staticLayout = staticLayout,
                    textOffsetX = textOffsetX,
                    textOffsetY = textOffsetY,
                    backgroundColor = bgColor,
                    textColor = txtColor,
                    backgroundPatch = backgroundPatch,
                    isTruncated = isTruncated,
                    revealDelay = (renderBoxes.size * 0.035f).coerceAtMost(0.45f)
                )
            )
        }
        Log.i("ArTranslateOverlayView", "Precomputed ${renderBoxes.size} fitted render boxes")
        startRevealAnimation()
    }

    @SuppressLint("WrongConstant")
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

    @SuppressLint("WrongConstant")
    private fun createTruncatedLayout(
        text: CharSequence,
        paint: TextPaint,
        width: Int,
        maxLines: Int
    ): StaticLayout {
        val safeWidth = max(1, width)
        val safeLines = max(1, maxLines)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .setMaxLines(safeLines)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(
                text,
                paint,
                safeWidth,
                Layout.Alignment.ALIGN_NORMAL,
                1.0f,
                0f,
                false
            )
        }
    }

    // --- Reveal animation (Material 3 Expressive-style stagger) ---

    private var revealProgress = 1f
    private var revealAnimator: ValueAnimator? = null

    private fun startRevealAnimation() {
        revealAnimator?.cancel()
        revealProgress = 0f
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                revealProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
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
            text = if (showOriginalText) context.getString(R.string.screen_ar_original) else context.getString(R.string.screen_ar_translate)
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
                text = if (showOriginalText) context.getString(R.string.screen_ar_original) else context.getString(R.string.screen_ar_translate)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 16 * density
                    setColor(if (showOriginalText) Color.parseColor("#CCF59E0B") else Color.parseColor("#CC2A2A38"))
                    setStroke((1 * density).toInt(), Color.parseColor("#44FFFFFF"))
                }
                precomputeLayouts()
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
            text = context.getString(R.string.screen_ar_save)
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
        val all = Language.allLanguages
            .map { it.code to it.displayName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase(Locale.getDefault()) }
        val languages = if (isSource) listOf("auto" to "Auto") + all else all

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
                text = if (code == "auto") "Auto" else "$name (${code.uppercase(Locale.ROOT)})"
                textSize = 12f
                typeface = Typeface.DEFAULT
                setTextColor(if (code == currentTargetLang || code == currentSourceLang) Color.parseColor("#80D8FF") else Color.WHITE)
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
        val pickerWidth = (170 * density).toInt()
        val minPickerX = (8 * density).toInt()
        val maxPickerX = max(minPickerX, screenWidth - pickerWidth - minPickerX)
        val posX = anchorLoc[0].safeCoerceIn(minPickerX, maxPickerX)
        val posY = (anchorLoc[1] + anchor.height + (4 * density).toInt())

        val estimatedHeight = (languages.size * 42 * density).toInt()
        val maxHeight = (screenHeight * 0.62f).toInt().coerceAtLeast((120 * density).toInt())
        val pickerHeight = min(estimatedHeight, maxHeight).coerceAtLeast((60 * density).toInt())

        val scroll = android.widget.ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(pickerLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        val params = LayoutParams(pickerWidth, pickerHeight).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = posX
            topMargin = posY
        }

        addView(scroll, params)
        activeLanguagePickerMenu = scroll
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
        "auto" -> context.getString(R.string.screen_ar_auto)
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
        this.selectedRenderBox = null
        dismissMicroToolbar()
        dismissLanguagePicker()
        updateTopBarLabels()

        animate().alpha(0.5f).setDuration(70L).withEndAction {
            precomputeLayouts()
            invalidate()
            animate().alpha(1.0f).setDuration(120L).start()
        }.start()
    }

    /**
     * Renders the exact in-place translation the user sees (without the top bar
     * or selection chrome) so "Save" cannot drift from the overlay layout.
     */
    fun renderComposite(): Bitmap? {
        val source = frozenBitmap?.takeIf { !it.isRecycled } ?: return null
        val result = source.copy(Bitmap.Config.ARGB_8888, true) ?: return null
        val canvas = Canvas(result)
        val cornerRadius = 3f * density
        for (item in renderBoxes) {
            if (item.isShowingOriginal || showOriginalText) continue
            val patch = item.backgroundPatch
            if (patch != null && !patch.isRecycled) {
                patchPaint.alpha = 255
                canvas.drawBitmap(patch, null, item.eraseRect, patchPaint)
            } else {
                erasePaint.color = item.backgroundColor
                erasePaint.alpha = 255
                canvas.drawRoundRect(item.eraseRect, cornerRadius, cornerRadius, erasePaint)
            }
            item.staticLayout.paint.alpha = 255
            canvas.save()
            canvas.clipRect(item.eraseRect)
            canvas.translate(item.textOffsetX, item.textOffsetY)
            item.staticLayout.draw(canvas)
            canvas.restore()
        }
        return result
    }

    // --- Drawing Engine ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val scaleX = width.toFloat() / screenWidth.coerceAtLeast(1).toFloat()
        val scaleY = height.toFloat() / screenHeight.coerceAtLeast(1).toFloat()
        canvas.save()
        canvas.scale(scaleX, scaleY)

        // 1. Frozen screenshot background in capture-pixel coordinates. The
        // canvas scale above maps it onto the overlay window exactly.
        frozenBitmap?.let { bmp ->
            if (!bmp.isRecycled && bmp.width > 0 && bmp.height > 0) {
                canvas.drawBitmap(bmp, 0f, 0f, screenshotPaint)
            }
        }

        // 2. Dim backdrop only for the legacy card style.
        if (overlayStyle != "inpainting") {
            canvas.drawRect(0f, 0f, screenWidth.toFloat(), screenHeight.toFloat(), backdropPaint)
        }

        val cornerRadius = 3f * density
        val dimmed = showOriginalText

        // 3. In-place replacement pass. Every box is drawn strictly inside its
        // original block; nothing is shifted over neighbouring UI.
        if (!dimmed) {
            for (item in renderBoxes) {
                if (item.isShowingOriginal) continue
                val alpha = ((revealProgress - item.revealDelay) / 0.55f).coerceIn(0f, 1f)
                if (alpha <= 0.01f) continue
                val alphaInt = (255f * alpha).toInt().coerceIn(0, 255)

                val patch = item.backgroundPatch
                if (patch != null && !patch.isRecycled) {
                    patchPaint.alpha = alphaInt
                    canvas.drawBitmap(patch, null, item.eraseRect, patchPaint)
                } else {
                    erasePaint.color = item.backgroundColor
                    erasePaint.alpha = alphaInt
                    canvas.drawRoundRect(item.eraseRect, cornerRadius, cornerRadius, erasePaint)
                }

                item.staticLayout.paint.alpha = alphaInt
                canvas.save()
                canvas.clipRect(item.eraseRect)
                canvas.translate(item.textOffsetX, item.textOffsetY)
                item.staticLayout.draw(canvas)
                canvas.restore()
            }
        }

        // 4. Selection highlight follows the fitted box, not a moved ghost.
        selectedRenderBox?.let { selected ->
            canvas.drawRoundRect(selected.pillRect, 6f * density, 6f * density, pillStrokeSelectedPaint)
        }

        canvas.restore()
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

                val touchScaleX = width.toFloat() / screenWidth.coerceAtLeast(1).toFloat()
                val touchScaleY = height.toFloat() / screenHeight.coerceAtLeast(1).toFloat()
                val touchX = event.x / touchScaleX.coerceAtLeast(0.001f)
                val touchY = event.y / touchScaleY.coerceAtLeast(0.001f)

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
        toolbar.addView(createMicroActionButton(context.getString(R.string.screen_ar_copy)) {
            val textToCopy = if (item.isShowingOriginal) item.origin.rawText else item.origin.translatedText
            val clip = context.getSystemService(ClipboardManager::class.java)
            clip?.setPrimaryClip(ClipData.newPlainText("Parlex", textToCopy))
            Toast.makeText(
                context,
                if (item.isShowingOriginal) context.getString(R.string.screen_ar_copied_original)
                else context.getString(R.string.screen_ar_copied_translation),
                Toast.LENGTH_SHORT
            ).show()
            dismissMicroToolbar()
        })

        // Action 2: Speak TTS
        toolbar.addView(createMicroActionButton(context.getString(R.string.screen_ar_speak)) {
            val textToSpeak = if (item.isShowingOriginal) item.origin.rawText else item.origin.translatedText
            tts?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, null, "ar_speak_${System.currentTimeMillis()}")
        })

        // Action 3: Toggle Original / Translation for this specific card.
        // Hiding the box reveals the untouched frozen screenshot region, so no
        // second layout pass and no stale selectedRenderBox are needed.
        val toggleLabel = if (item.isShowingOriginal) {
            context.getString(R.string.screen_ar_translate)
        } else {
            context.getString(R.string.screen_ar_original)
        }
        toolbar.addView(createMicroActionButton(toggleLabel) {
            item.isShowingOriginal = !item.isShowingOriginal
            invalidate()
            dismissMicroToolbar()
            selectedRenderBox = item
        })

        // Action 4: Hide Individual Pill
        toolbar.addView(createMicroActionButton(context.getString(R.string.screen_ar_hide)) {
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
        startRevealAnimation()
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
        revealAnimator?.cancel()
        revealAnimator = null
        renderBoxes.forEach { runCatching { it.backgroundPatch?.recycle() } }
        renderBoxes.clear()
        runCatching {
            tts?.stop()
            tts?.shutdown()
        }
        tts = null
    }
}
