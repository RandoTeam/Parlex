package com.translive.app.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.translive.app.data.model.Language

/**
 * Material Design 3 Mini Overlay HUD for Parlex Screen Translation.
 *
 * Implements strict zero-emoji styling, segmented translation engine selection,
 * horizontal quick target-language carousel, and outside-touch auto-dismissal.
 */
class MiniOverlayHudView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    interface MiniOverlayHudListener {
        fun onTargetLanguageSelected(language: Language)
        fun onEngineModeChanged(isLlm: Boolean)
        fun onScanRequested()
        fun onSaveScreenshotRequested()
        fun onCloseRequested()
        fun onDismissRequested()
    }

    var listener: MiniOverlayHudListener? = null

    private val density = context.resources.displayMetrics.density

    private val colorSurfaceContainer = Color.parseColor("#1C1B1F")
    private val colorSurfaceVariant = Color.parseColor("#2B2930")
    private val colorPrimary = Color.parseColor("#D0BCFF")
    private val colorPrimaryContainer = Color.parseColor("#4F378B")
    private val colorOnPrimaryContainer = Color.parseColor("#EADDFF")
    private val colorOnSurface = Color.parseColor("#E6E1E5")
    private val colorOnSurfaceVariant = Color.parseColor("#CAC4D0")
    private val colorOutline = Color.parseColor("#49454F")

    private var isLlmMode: Boolean = false
    private var currentTargetLanguage: Language = Language.ENGLISH

    private lateinit var mainCard: LinearLayout
    private lateinit var nmtSegmentBtn: TextView
    private lateinit var llmSegmentBtn: TextView
    private lateinit var langChipsContainer: LinearLayout
    private val langChipViews = mutableMapOf<String, TextView>()

    private var animScaleAlpha: ValueAnimator? = null

    init {
        setupViewHierarchy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupViewHierarchy() {
        val pad16 = (16 * density).toInt()
        val pad12 = (12 * density).toInt()
        val pad8 = (8 * density).toInt()

        mainCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad16, pad12, pad16, pad12)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 20f * density
                setColor(colorSurfaceContainer)
                setStroke((1 * density).toInt(), colorOutline)
            }
            elevation = 12f * density
            layoutParams = LayoutParams(
                (280 * density).toInt(),
                LayoutParams.WRAP_CONTENT
            )
        }

        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = pad8
            }
        }

        val titleContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val titleText = TextView(context).apply {
            text = "Parlex Translate"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colorOnSurface)
        }

        val subtitleText = TextView(context).apply {
            text = "Screen Translation HUD"
            textSize = 11f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setTextColor(colorOnSurfaceVariant)
        }

        titleContainer.addView(titleText)
        titleContainer.addView(subtitleText)
        headerLayout.addView(titleContainer)

        val closeBtn = TextView(context).apply {
            text = "X"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colorOnSurfaceVariant)
            gravity = Gravity.CENTER
            val btnSize = (28 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(btnSize, btnSize)
            background = createRipplePillDrawable(colorSurfaceVariant, colorOutline)
            setOnClickListener {
                animateDismiss { listener?.onDismissRequested() }
            }
        }
        headerLayout.addView(closeBtn)
        mainCard.addView(headerLayout)

        val segmentContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt(), (3 * density).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * density
                setColor(colorSurfaceVariant)
                setStroke((1 * density).toInt(), colorOutline)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * density).toInt()
            ).apply {
                bottomMargin = pad12
            }
        }

        nmtSegmentBtn = TextView(context).apply {
            text = "Fast NMT"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                setEngineMode(isLlm = false, dispatchCallback = true)
            }
        }

        llmSegmentBtn = TextView(context).apply {
            text = "Smart LLM"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener {
                setEngineMode(isLlm = true, dispatchCallback = true)
            }
        }

        segmentContainer.addView(nmtSegmentBtn)
        segmentContainer.addView(llmSegmentBtn)
        mainCard.addView(segmentContainer)

        val targetLabel = TextView(context).apply {
            text = "Target Language"
            textSize = 11f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colorOnSurfaceVariant)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4 * density).toInt()
            }
        }
        mainCard.addView(targetLabel)

        val scrollContainer = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = pad12
            }
        }

        langChipsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val popularLangs = listOf(
            Language.RUSSIAN,
            Language.ENGLISH,
            Language.CHINESE_SIMPLIFIED,
            Language.GERMAN,
            Language.FRENCH,
            Language.SPANISH,
            Language.VIETNAMESE,
            Language.JAPANESE,
            Language.KOREAN
        )
        val sortedLangs = popularLangs + Language.primaryLanguages.filter { it !in popularLangs }
        for (lang in sortedLangs) {
            val chip = createLanguageChip(lang)
            langChipViews[lang.code] = chip
            langChipsContainer.addView(chip)
        }

        scrollContainer.addView(langChipsContainer)
        mainCard.addView(scrollContainer)

        val saveScreenshotBtn = TextView(context).apply {
            text = "Save Screenshot"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colorOnSurface)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (36 * density).toInt()
            ).apply {
                bottomMargin = pad8
            }
            background = createRipplePillDrawable(colorSurfaceVariant, colorOutline)
            setOnClickListener {
                animateDismiss { listener?.onSaveScreenshotRequested() }
            }
        }
        mainCard.addView(saveScreenshotBtn)

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val closeServiceBtn = TextView(context).apply {
            text = "Close"
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colorOnSurfaceVariant)
            gravity = Gravity.CENTER
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                (38 * density).toInt()
            ).apply {
                marginEnd = pad8
            }
            background = createRipplePillDrawable(Color.TRANSPARENT, colorOutline)
            setOnClickListener {
                animateDismiss { listener?.onCloseRequested() }
            }
        }

        val scanBtn = TextView(context).apply {
            text = "Scan Screen"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(colorOnPrimaryContainer)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, (38 * density).toInt(), 1f)
            background = createRipplePillDrawable(colorPrimaryContainer, colorPrimary)
            setOnClickListener {
                animateDismiss { listener?.onScanRequested() }
            }
        }

        actionRow.addView(closeServiceBtn)
        actionRow.addView(scanBtn)
        mainCard.addView(actionRow)

        addView(mainCard)
        updateSegmentVisuals()
    }

    private fun createLanguageChip(lang: Language): TextView {
        val padH = (12 * density).toInt()
        val padV = (6 * density).toInt()
        val marginEnd = (6 * density).toInt()

        return TextView(context).apply {
            text = lang.code.uppercase()
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(padH, padV, padH, padV)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.marginEnd = marginEnd
            }
            setOnClickListener {
                setTargetLanguage(lang, dispatchCallback = true)
            }
        }
    }

    fun setState(targetLang: Language, isLlm: Boolean) {
        this.currentTargetLanguage = targetLang
        this.isLlmMode = isLlm
        updateSegmentVisuals()
        updateLanguageChipVisuals()
    }

    fun setEngineMode(isLlm: Boolean, dispatchCallback: Boolean) {
        if (this.isLlmMode == isLlm) return
        this.isLlmMode = isLlm
        updateSegmentVisuals()
        if (dispatchCallback) {
            listener?.onEngineModeChanged(isLlm)
        }
    }

    fun setTargetLanguage(language: Language, dispatchCallback: Boolean) {
        this.currentTargetLanguage = language
        updateLanguageChipVisuals()
        if (dispatchCallback) {
            listener?.onTargetLanguageSelected(language)
        }
    }

    private fun updateSegmentVisuals() {
        if (!isLlmMode) {
            nmtSegmentBtn.setTextColor(colorOnPrimaryContainer)
            nmtSegmentBtn.background = createPillDrawable(colorPrimaryContainer, colorPrimary)
            llmSegmentBtn.setTextColor(colorOnSurfaceVariant)
            llmSegmentBtn.background = null
        } else {
            llmSegmentBtn.setTextColor(colorOnPrimaryContainer)
            llmSegmentBtn.background = createPillDrawable(colorPrimaryContainer, colorPrimary)
            nmtSegmentBtn.setTextColor(colorOnSurfaceVariant)
            nmtSegmentBtn.background = null
        }
    }

    private fun updateLanguageChipVisuals() {
        for ((code, chip) in langChipViews) {
            val isSelected = code.equals(currentTargetLanguage.code, ignoreCase = true)
            if (isSelected) {
                chip.setTextColor(colorOnPrimaryContainer)
                chip.typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                chip.background = createPillDrawable(colorPrimaryContainer, colorPrimary)
            } else {
                chip.setTextColor(colorOnSurfaceVariant)
                chip.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                chip.background = createPillDrawable(colorSurfaceVariant, colorOutline)
            }
        }
    }

    fun animateEntrance() {
        animScaleAlpha?.cancel()
        scaleX = 0.85f
        scaleY = 0.85f
        alpha = 0f

        animScaleAlpha = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = OvershootInterpolator(1.1f)
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                scaleX = 0.85f + (0.15f * fraction)
                scaleY = 0.85f + (0.15f * fraction)
                alpha = fraction
            }
            start()
        }
    }

    fun animateDismiss(onComplete: () -> Unit) {
        animScaleAlpha?.cancel()
        animScaleAlpha = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 180L
            interpolator = AccelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                scaleX = 0.85f + (0.15f * fraction)
                scaleY = 0.85f + (0.15f * fraction)
                alpha = fraction
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onComplete()
                }
            })
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            animateDismiss { listener?.onDismissRequested() }
            return true
        }
        return super.onTouchEvent(event)
    }

    private fun createPillDrawable(fillColor: Int, strokeColor: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(fillColor)
            setStroke((1 * density).toInt(), strokeColor)
        }
    }

    private fun createRipplePillDrawable(fillColor: Int, strokeColor: Int): RippleDrawable {
        val content = createPillDrawable(fillColor, strokeColor)
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(Color.WHITE)
        }
        val rippleColor = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
        return RippleDrawable(rippleColor, content, mask)
    }

    override fun onDetachedFromWindow() {
        animScaleAlpha?.cancel()
        animScaleAlpha = null
        listener = null
        super.onDetachedFromWindow()
    }
}
