package com.translive.app.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import kotlin.math.abs

class FloatingBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class BubbleState {
        IDLE,
        SCANNING,
        TRANSLATING,
        COMPLETE,
        ERROR,
        DISABLED
    }

    enum class DockEdge {
        LEFT,
        RIGHT
    }

    interface BubbleEventListener {
        fun onBubbleClick()
        fun onBubbleBusyClick(currentState: BubbleState)
        fun onBubbleLongClick()
        fun onPositionChanged(x: Int, y: Int)
        fun onDocked(edge: DockEdge, y: Int)
        fun onDragStart() {}
        fun onDragMove(bubbleCenterX: Float, bubbleCenterY: Float) {}
        fun onDragRelease(): Boolean = false
    }

    var listener: BubbleEventListener? = null
    var bubbleState: BubbleState = BubbleState.IDLE
        private set

    private val density: Float = context.resources.displayMetrics.density
    private val viewSizeDp = 76f
    private val coreRadiusDp = 28f
    private val strokeWidthDp = 2.5f

    val totalViewSizePx: Int = (viewSizeDp * density).toInt()
    private val coreRadiusPx: Float = coreRadiusDp * density
    private val strokeWidthPx: Float = strokeWidthDp * density

    // Material 3 Expressive / Pixel-like tonal glass palette.
    private val colorIdleCore = Color.parseColor("#101820")       // Ink glass
    private val colorIdleStroke = Color.parseColor("#8AB4F8")     // Pixel blue rim
    private val colorIdleAura = Color.parseColor("#A8C7FA")

    private val colorScanCore = Color.parseColor("#3B2A12")       // Warm glass
    private val colorScanStroke = Color.parseColor("#FFD08A")
    private val colorScanAura = Color.parseColor("#FFB95C")

    private val colorTransCore = Color.parseColor("#0B2A4A")      // Deep blue glass
    private val colorTransStroke = Color.parseColor("#A8C7FA")
    private val colorTransAura = Color.parseColor("#8AB4F8")

    private val colorCompleteCore = Color.parseColor("#0E2B20")   // Deep green glass
    private val colorCompleteStroke = Color.parseColor("#7DD3A8")
    private val colorCompleteAura = Color.parseColor("#34A853")

    private val colorErrorCore = Color.parseColor("#3B1212")      // Deep red glass
    private val colorErrorStroke = Color.parseColor("#FFB4AB")
    private val colorErrorAura = Color.parseColor("#FF897D")

    private val colorShadow = Color.argb(64, 0, 0, 0)
    private val argbEvaluator = ArgbEvaluator()

    private var currentCoreColor = colorIdleCore
    private var currentStrokeColor = colorIdleStroke
    private var currentAuraColor = colorIdleAura
    private var currentGlyph = "T"

    private val coreFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = currentCoreColor
    }

    private val coreStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = currentStrokeColor
    }

    private val auraPaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = currentAuraColor
    }

    private val auraPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = currentAuraColor
    }

    private val scanSweepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorShadow
    }

    private val glassHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.parseColor("#4DFFFFFF")
    }

    private val glassRimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * density
        color = Color.parseColor("#66FFFFFF")
    }

    private val specularPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val innerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#1FFFFFFF")
    }

    private val vectorIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val coreBounds = RectF()
    private val scanRingBounds = RectF()
    private var centerX = 0f
    private var centerY = 0f

    private var colorAnimator: ValueAnimator? = null
    private var pulseAnimator: ValueAnimator? = null
    private var scanRotationAnimator: ValueAnimator? = null
    private var dockAnimator: ValueAnimator? = null

    private var pulseProgress = 0f
    private var scanRotationAngle = 0f

    private var windowManager: WindowManager? = null
    private var windowParams: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var velocityTracker: VelocityTracker? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 1.5f
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()

    private var initialTouchRawX = 0f
    private var initialTouchRawY = 0f
    private var initialParamX = 0
    private var initialParamY = 0
    private var isDragging = false
    private var isLongPressTriggered = false

    private var lastClickTimestamp = 0L
    private val clickDebounceThresholdMs = 400L

    private val autoResetRunnable = Runnable {
        if (bubbleState == BubbleState.ERROR || bubbleState == BubbleState.COMPLETE) {
            setState(BubbleState.IDLE)
        }
    }

    private val longPressRunnable = Runnable {
        if (!isDragging && bubbleState == BubbleState.IDLE) {
            isLongPressTriggered = true
            performMicroHaptic(HapticFeedbackType.LONG_PRESS)
            listener?.onBubbleLongClick()
        }
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(com.translive.app.R.string.screen_translate_title)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun attachToWindowManager(wm: WindowManager, params: WindowManager.LayoutParams) {
        this.windowManager = wm
        this.windowParams = params
    }

    /**
     * Called when the overlay window is shown/hidden or the bubble is reattached.
     * Without this reset a cancelled drag (the AR overlay taking touch focus) can
     * leave the bubble stuck in `isDragging` and make it impossible to move.
     */
    fun resetInteractionState() {
        mainHandler.removeCallbacks(longPressRunnable)
        mainHandler.removeCallbacks(autoResetRunnable)
        isDragging = false
        isLongPressTriggered = false
        dockAnimator?.cancel()
        dockAnimator = null
        recycleVelocityTracker()
        if (bubbleState == BubbleState.COMPLETE || bubbleState == BubbleState.ERROR) {
            setState(BubbleState.IDLE)
        }
        alpha = if (bubbleState == BubbleState.DISABLED) 0.4f else 1f
        scaleX = 1f
        scaleY = 1f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(totalViewSizePx, totalViewSizePx)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        coreBounds.set(
            centerX - coreRadiusPx,
            centerY - coreRadiusPx,
            centerX + coreRadiusPx,
            centerY + coreRadiusPx
        )
        val ringPadding = 6f * density
        scanRingBounds.set(
            coreBounds.left - ringPadding,
            coreBounds.top - ringPadding,
            coreBounds.right + ringPadding,
            coreBounds.bottom + ringPadding
        )

        // Fixed specular highlight baked into a shader; the bubble itself stays
        // cheap to redraw while the state ring/pulse animates.
        specularPaint.shader = RadialGradient(
            centerX - coreRadiusPx * 0.32f,
            centerY - coreRadiusPx * 0.38f,
            coreRadiusPx * 1.28f,
            intArrayOf(
                Color.parseColor("#66FFFFFF"),
                Color.parseColor("#1FFFFFFF"),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.48f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        when (bubbleState) {
            BubbleState.SCANNING -> drawScanningSweep(canvas)
            BubbleState.TRANSLATING -> drawTranslatingPulse(canvas)
            BubbleState.COMPLETE -> drawCompleteGlow(canvas)
            BubbleState.IDLE, BubbleState.ERROR, BubbleState.DISABLED -> {}
        }

        shadowPaint.color = colorShadow
        canvas.drawCircle(centerX, centerY + (3.0f * density), coreRadiusPx + (3f * density), shadowPaint)
        shadowPaint.alpha = 38
        canvas.drawCircle(centerX, centerY + (1.5f * density), coreRadiusPx, shadowPaint)

        // A very soft tonal aura keeps the bubble legible on both light and dark
        // wallpapers without the old neon ring.
        auraPaint1.color = (currentAuraColor and 0x00FFFFFF) or (30 shl 24)
        auraPaint1.strokeWidth = 9f * density
        canvas.drawCircle(centerX, centerY, coreRadiusPx + (3f * density), auraPaint1)

        coreFillPaint.color = currentCoreColor
        coreFillPaint.alpha = 238
        coreStrokePaint.color = currentStrokeColor
        canvas.drawCircle(centerX, centerY, coreRadiusPx, coreFillPaint)

        // Glass body: specular highlight, inner tint, rim and a thin refraction
        // arc. No blur layer, so the control remains crisp at 120 Hz.
        canvas.drawCircle(centerX, centerY, coreRadiusPx - (0.5f * density), specularPaint)
        innerGlowPaint.alpha = 20
        canvas.drawCircle(centerX, centerY, coreRadiusPx - (5f * density), innerGlowPaint)
        canvas.drawCircle(centerX, centerY, coreRadiusPx - (strokeWidthPx / 2f), coreStrokePaint)
        glassRimPaint.alpha = 120
        canvas.drawCircle(centerX, centerY, coreRadiusPx - (1.2f * density), glassRimPaint)

        // Liquid Glass Specular Reflection Highlight
        val highlightBounds = RectF(
            centerX - coreRadiusPx + (3f * density),
            centerY - coreRadiusPx + (3f * density),
            centerX + coreRadiusPx - (3f * density),
            centerY + coreRadiusPx - (3f * density)
        )
        glassHighlightPaint.alpha = 150
        canvas.drawArc(highlightBounds, 205f, 125f, false, glassHighlightPaint)
        glassHighlightPaint.alpha = 48
        canvas.drawArc(highlightBounds, 25f, 80f, false, glassHighlightPaint)

        if (bubbleState == BubbleState.IDLE || bubbleState == BubbleState.DISABLED) {
            drawTranslationVectorGlyph(canvas)
        } else {
            val textYOffset = (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(currentGlyph, centerX, centerY - textYOffset, textPaint)
        }
    }

    private fun drawTranslationVectorGlyph(canvas: Canvas) {
        val span = 8.5f * density
        val offset = 3f * density

        // Upper arrow (pointing right)
        canvas.drawLine(centerX - span, centerY - offset, centerX + span, centerY - offset, vectorIconPaint)
        canvas.drawLine(centerX + span - (4f * density), centerY - offset - (3f * density), centerX + span, centerY - offset, vectorIconPaint)
        canvas.drawLine(centerX + span - (4f * density), centerY - offset + (3f * density), centerX + span, centerY - offset, vectorIconPaint)

        // Lower arrow (pointing left)
        canvas.drawLine(centerX + span, centerY + offset, centerX - span, centerY + offset, vectorIconPaint)
        canvas.drawLine(centerX - span + (4f * density), centerY + offset - (3f * density), centerX - span, centerY + offset, vectorIconPaint)
        canvas.drawLine(centerX - span + (4f * density), centerY + offset + (3f * density), centerX - span, centerY + offset, vectorIconPaint)
    }

    private fun drawScanningSweep(canvas: Canvas) {
        canvas.save()
        canvas.rotate(scanRotationAngle, centerX, centerY)
        scanSweepPaint.color = currentAuraColor
        scanSweepPaint.alpha = 220
        canvas.drawArc(scanRingBounds, 0f, 120f, false, scanSweepPaint)
        canvas.drawArc(scanRingBounds, 180f, 60f, false, scanSweepPaint)
        canvas.restore()
    }

    private fun drawTranslatingPulse(canvas: Canvas) {
        val maxAuraExpansion = 16f * density

        val progress1 = pulseProgress
        val radius1 = coreRadiusPx + (progress1 * maxAuraExpansion)
        val alpha1 = ((1f - progress1) * 0.8f * 255).toInt().coerceIn(0, 255)
        auraPaint1.color = (currentAuraColor and 0x00FFFFFF) or (alpha1 shl 24)
        auraPaint1.strokeWidth = (2.8f * density) * (1f - progress1 * 0.4f)
        canvas.drawCircle(centerX, centerY, radius1, auraPaint1)

        val progress2 = (pulseProgress + 0.5f) % 1.0f
        val radius2 = coreRadiusPx + (progress2 * maxAuraExpansion * 1.25f)
        val alpha2 = ((1f - progress2) * 0.5f * 255).toInt().coerceIn(0, 255)
        auraPaint2.color = (currentAuraColor and 0x00FFFFFF) or (alpha2 shl 24)
        auraPaint2.strokeWidth = (2f * density) * (1f - progress2 * 0.5f)
        canvas.drawCircle(centerX, centerY, radius2, auraPaint2)
    }

    private fun drawCompleteGlow(canvas: Canvas) {
        val glowRadius = coreRadiusPx + (5f * density)
        auraPaint1.color = (colorCompleteAura and 0x00FFFFFF) or (140 shl 24)
        auraPaint1.strokeWidth = 3f * density
        canvas.drawCircle(centerX, centerY, glowRadius, auraPaint1)
    }

    fun setState(newState: BubbleState) {
        if (bubbleState == newState) return
        val oldState = bubbleState
        bubbleState = newState

        mainHandler.removeCallbacks(autoResetRunnable)

        currentGlyph = when (newState) {
            BubbleState.IDLE -> "T"
            BubbleState.SCANNING -> "⌕"
            BubbleState.TRANSLATING -> "⇄"
            BubbleState.COMPLETE -> "✓"
            BubbleState.ERROR -> "!"
            BubbleState.DISABLED -> "T"
        }

        animateColorTransition(oldState, newState)

        when (newState) {
            BubbleState.SCANNING -> {
                stopPulseAnimation()
                startScanRotation()
            }
            BubbleState.TRANSLATING -> {
                stopScanRotation()
                startPulseAnimation()
            }
            BubbleState.ERROR -> {
                stopScanRotation()
                stopPulseAnimation()
                performMicroHaptic(HapticFeedbackType.WARNING)
                mainHandler.postDelayed(autoResetRunnable, 2000L)
            }
            BubbleState.COMPLETE -> {
                stopScanRotation()
                stopPulseAnimation()
                mainHandler.postDelayed(autoResetRunnable, 2500L)
            }
            BubbleState.IDLE, BubbleState.DISABLED -> {
                stopScanRotation()
                stopPulseAnimation()
            }
        }

        alpha = if (newState == BubbleState.DISABLED) 0.4f else 1.0f
        invalidate()
    }

    private fun animateColorTransition(fromState: BubbleState, toState: BubbleState) {
        colorAnimator?.cancel()

        val (targetCore, targetStroke, targetAura) = when (toState) {
            BubbleState.IDLE -> Triple(colorIdleCore, colorIdleStroke, colorIdleAura)
            BubbleState.SCANNING -> Triple(colorScanCore, colorScanStroke, colorScanAura)
            BubbleState.TRANSLATING -> Triple(colorTransCore, colorTransStroke, colorTransAura)
            BubbleState.COMPLETE -> Triple(colorCompleteCore, colorCompleteStroke, colorCompleteAura)
            BubbleState.ERROR -> Triple(colorErrorCore, colorErrorStroke, colorErrorAura)
            BubbleState.DISABLED -> Triple(colorIdleCore, colorIdleStroke, colorIdleAura)
        }

        val startCore = currentCoreColor
        val startStroke = currentStrokeColor
        val startAura = currentAuraColor

        colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                currentCoreColor = argbEvaluator.evaluate(f, startCore, targetCore) as Int
                currentStrokeColor = argbEvaluator.evaluate(f, startStroke, targetStroke) as Int
                currentAuraColor = argbEvaluator.evaluate(f, startAura, targetAura) as Int
                invalidate()
            }
            start()
        }
    }

    private fun startScanRotation() {
        if (scanRotationAnimator?.isRunning == true) return
        scanRotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                scanRotationAngle = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopScanRotation() {
        scanRotationAnimator?.cancel()
        scanRotationAnimator = null
        scanRotationAngle = 0f
    }

    private fun startPulseAnimation() {
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                pulseProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulseProgress = 0f
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (bubbleState == BubbleState.DISABLED) return false

        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain()
        }
        velocityTracker?.addMovement(event)

        val params = windowParams ?: return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dockAnimator?.cancel()
                animatePress(true)
                initialTouchRawX = event.rawX
                initialTouchRawY = event.rawY
                initialParamX = params.x
                initialParamY = params.y
                isDragging = false
                isLongPressTriggered = false

                mainHandler.postDelayed(longPressRunnable, longPressTimeout)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchRawX
                val dy = event.rawY - initialTouchRawY

                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                    mainHandler.removeCallbacks(longPressRunnable)
                    listener?.onDragStart()
                }

                if (isDragging) {
                    params.x = (initialParamX + dx).toInt()
                    params.y = (initialParamY + dy).toInt()
                    updateWindowLayout()
                    listener?.onPositionChanged(params.x, params.y)
                    val bubbleCenterX = params.x + (totalViewSizePx / 2f)
                    val bubbleCenterY = params.y + (totalViewSizePx / 2f)
                    listener?.onDragMove(bubbleCenterX, bubbleCenterY)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)

                if (!isDragging && !isLongPressTriggered) {
                    performClick()
                    handleClickWithDebounce()
                } else if (isDragging) {
                    val isDismissed = listener?.onDragRelease() ?: false
                    if (!isDismissed) {
                        velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity)
                        val xVelocity = velocityTracker?.xVelocity ?: 0f
                        val yVelocity = velocityTracker?.yVelocity ?: 0f
                        performAutoDocking(xVelocity, yVelocity)
                    }
                }

                recycleVelocityTracker()
                animatePress(false)
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                animatePress(false)
                if (isDragging) {
                    isDragging = false
                    val isDismissed = listener?.onDragRelease() ?: false
                    if (!isDismissed) {
                        performAutoDocking(0f, 0f)
                    }
                }
                recycleVelocityTracker()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleClickWithDebounce() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastClickTimestamp < clickDebounceThresholdMs) {
            return
        }
        lastClickTimestamp = now

        if (bubbleState == BubbleState.COMPLETE || bubbleState == BubbleState.ERROR) {
            mainHandler.removeCallbacks(autoResetRunnable)
            setState(BubbleState.IDLE)
        }

        if (bubbleState == BubbleState.SCANNING || bubbleState == BubbleState.TRANSLATING) {
            performMicroHaptic(HapticFeedbackType.WARNING)
            listener?.onBubbleBusyClick(bubbleState)
            return
        }

        performMicroHaptic(HapticFeedbackType.TAP)
        listener?.onBubbleClick()
    }

    private fun animatePress(pressed: Boolean) {
        animate()
            .scaleX(if (pressed) 0.92f else 1f)
            .scaleY(if (pressed) 0.92f else 1f)
            .setDuration(if (pressed) 90L else 160L)
            .setInterpolator(if (pressed) AccelerateDecelerateInterpolator() else OvershootInterpolator(0.9f))
            .start()
    }

    private fun recycleVelocityTracker() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun performAutoDocking(xVelocity: Float, yVelocity: Float) {
        val params = windowParams ?: return
        val displayMetrics = context.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val margin = (12 * density).toInt()

        val leftDockX = margin
        val rightDockX = screenWidth - totalViewSizePx - margin

        val targetX = when {
            xVelocity > minFlingVelocity -> rightDockX
            xVelocity < -minFlingVelocity -> leftDockX
            else -> {
                val bubbleCenterX = params.x + (totalViewSizePx / 2)
                if (bubbleCenterX < screenWidth / 2) leftDockX else rightDockX
            }
        }

        val topBound = (48 * density).toInt()
        val bottomBound = screenHeight - totalViewSizePx - (48 * density).toInt()
        val targetY = params.y.coerceIn(topBound, bottomBound)

        val startX = params.x
        val startY = params.y

        dockAnimator?.cancel()
        dockAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280L
            interpolator = OvershootInterpolator(0.65f)
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                params.x = (startX + (targetX - startX) * fraction).toInt()
                params.y = (startY + (targetY - startY) * fraction).toInt()
                updateWindowLayout()
                listener?.onPositionChanged(params.x, params.y)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    performMicroHaptic(HapticFeedbackType.DOCK_SNAP)
                    val edge = if (targetX == leftDockX) DockEdge.LEFT else DockEdge.RIGHT
                    listener?.onDocked(edge, params.y)
                }
            })
            start()
        }
    }

    private fun updateWindowLayout() {
        windowParams?.let { params ->
            runCatching { windowManager?.updateViewLayout(this, params) }
        }
    }

    private enum class HapticFeedbackType {
        TAP,
        LONG_PRESS,
        DOCK_SNAP,
        WARNING
    }

    private fun performMicroHaptic(type: HapticFeedbackType) {
        val hapticConstant = when (type) {
            HapticFeedbackType.TAP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            HapticFeedbackType.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
            HapticFeedbackType.DOCK_SNAP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) HapticFeedbackConstants.TEXT_HANDLE_MOVE else HapticFeedbackConstants.VIRTUAL_KEY
            HapticFeedbackType.WARNING -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.VIRTUAL_KEY
        }

        val performed = performHapticFeedback(
            hapticConstant,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )

        if (!performed) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val effectId = when (type) {
                        HapticFeedbackType.TAP -> VibrationEffect.EFFECT_CLICK
                        HapticFeedbackType.LONG_PRESS -> VibrationEffect.EFFECT_HEAVY_CLICK
                        HapticFeedbackType.DOCK_SNAP -> VibrationEffect.EFFECT_TICK
                        HapticFeedbackType.WARNING -> VibrationEffect.EFFECT_DOUBLE_CLICK
                    }
                    vibrator?.vibrate(VibrationEffect.createPredefined(effectId))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(15L, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(15L)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacksAndMessages(null)
        stopScanRotation()
        stopPulseAnimation()
        colorAnimator?.cancel()
        dockAnimator?.cancel()
        recycleVelocityTracker()
        windowManager = null
        windowParams = null
        super.onDetachedFromWindow()
    }
}
