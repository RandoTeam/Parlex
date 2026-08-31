package com.translive.app.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import android.view.animation.OvershootInterpolator
import kotlin.math.abs

class FloatingBubbleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class BubbleState {
        IDLE,
        CAPTURING,
        PROCESSING,
        DISABLED
    }

    enum class DockEdge {
        LEFT,
        RIGHT
    }

    interface BubbleEventListener {
        fun onBubbleClick()
        fun onBubbleLongClick()
        fun onPositionChanged(x: Int, y: Int)
        fun onDocked(edge: DockEdge, y: Int)
    }

    var listener: BubbleEventListener? = null
    var bubbleState: BubbleState = BubbleState.IDLE
        private set

    private val density: Float = context.resources.displayMetrics.density
    private val viewSizeDp = 76f
    private val coreRadiusDp = 28f
    private val strokeWidthDp = 2f

    val totalViewSizePx: Int = (viewSizeDp * density).toInt()
    private val coreRadiusPx: Float = coreRadiusDp * density
    private val strokeWidthPx: Float = strokeWidthDp * density

    private val colorPrimaryContainer = Color.parseColor("#4F378B")
    private val colorOutline = Color.parseColor("#D0BCFF")
    private val colorOnSurface = Color.parseColor("#E6E1E5")
    private val colorAuraRing = Color.parseColor("#D0BCFF")
    private val colorShadow = Color.argb(90, 0, 0, 0)

    private val coreFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorPrimaryContainer
    }

    private val coreStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = colorOutline
        strokeWidth = strokeWidthPx
    }

    private val auraPaint1 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = colorAuraRing
    }

    private val auraPaint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = colorAuraRing
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorOnSurface
        textSize = 22f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colorShadow
    }

    private val coreBounds = RectF()
    private var centerX = 0f
    private var centerY = 0f

    private var auraAnimator: ValueAnimator? = null
    private var auraProgress: Float = 0f
    private var dockAnimator: ValueAnimator? = null

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

    private val longPressRunnable = Runnable {
        if (!isDragging) {
            isLongPressTriggered = true
            performMicroHaptic(HapticFeedbackType.LONG_PRESS)
            listener?.onBubbleLongClick()
        }
    }

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = false
    }

    fun attachToWindowManager(wm: WindowManager, params: WindowManager.LayoutParams) {
        this.windowManager = wm
        this.windowParams = params
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
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (bubbleState == BubbleState.CAPTURING || bubbleState == BubbleState.PROCESSING) {
            drawConcentricAura(canvas)
        }

        canvas.drawCircle(centerX, centerY + (2f * density), coreRadiusPx, shadowPaint)
        canvas.drawCircle(centerX, centerY, coreRadiusPx, coreFillPaint)
        canvas.drawCircle(centerX, centerY, coreRadiusPx - (strokeWidthPx / 2f), coreStrokePaint)

        val textYOffset = (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText("T", centerX, centerY - textYOffset, textPaint)
    }

    private fun drawConcentricAura(canvas: Canvas) {
        val maxAuraExpansion = 16f * density

        val progress1 = auraProgress
        val radius1 = coreRadiusPx + (progress1 * maxAuraExpansion)
        val alpha1 = ((1f - progress1) * 0.75f * 255).toInt().coerceIn(0, 255)
        auraPaint1.color = (colorAuraRing and 0x00FFFFFF) or (alpha1 shl 24)
        auraPaint1.strokeWidth = (2.5f * density) * (1f - progress1 * 0.4f)
        canvas.drawCircle(centerX, centerY, radius1, auraPaint1)

        val progress2 = (auraProgress + 0.5f) % 1.0f
        val radius2 = coreRadiusPx + (progress2 * maxAuraExpansion * 1.25f)
        val alpha2 = ((1f - progress2) * 0.45f * 255).toInt().coerceIn(0, 255)
        auraPaint2.color = (colorAuraRing and 0x00FFFFFF) or (alpha2 shl 24)
        auraPaint2.strokeWidth = (2f * density) * (1f - progress2 * 0.5f)
        canvas.drawCircle(centerX, centerY, radius2, auraPaint2)
    }

    fun setState(newState: BubbleState) {
        if (bubbleState == newState) return
        bubbleState = newState

        when (newState) {
            BubbleState.CAPTURING, BubbleState.PROCESSING -> startAuraAnimation()
            BubbleState.IDLE, BubbleState.DISABLED -> stopAuraAnimation()
        }

        alpha = if (newState == BubbleState.DISABLED) 0.4f else 1.0f
        invalidate()
    }

    private fun startAuraAnimation() {
        if (auraAnimator?.isRunning == true) return
        auraAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                auraProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAuraAnimation() {
        auraAnimator?.cancel()
        auraAnimator = null
        auraProgress = 0f
        invalidate()
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
                }

                if (isDragging) {
                    params.x = (initialParamX + dx).toInt()
                    params.y = (initialParamY + dy).toInt()
                    updateWindowLayout()
                    listener?.onPositionChanged(params.x, params.y)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                mainHandler.removeCallbacks(longPressRunnable)

                if (!isDragging && !isLongPressTriggered) {
                    performMicroHaptic(HapticFeedbackType.TAP)
                    listener?.onBubbleClick()
                } else if (isDragging) {
                    velocityTracker?.computeCurrentVelocity(1000, maxFlingVelocity)
                    val xVelocity = velocityTracker?.xVelocity ?: 0f
                    val yVelocity = velocityTracker?.yVelocity ?: 0f
                    performAutoDocking(xVelocity, yVelocity)
                }

                recycleVelocityTracker()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)
                if (isDragging) {
                    performAutoDocking(0f, 0f)
                }
                recycleVelocityTracker()
                return true
            }
        }
        return super.onTouchEvent(event)
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
        DOCK_SNAP
    }

    private fun performMicroHaptic(type: HapticFeedbackType) {
        val hapticConstant = when (type) {
            HapticFeedbackType.TAP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            }
            HapticFeedbackType.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
            HapticFeedbackType.DOCK_SNAP -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    HapticFeedbackConstants.TEXT_HANDLE_MOVE
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
            }
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
                    }
                    vibrator?.vibrate(VibrationEffect.createPredefined(effectId))
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val durationMs = when (type) {
                        HapticFeedbackType.TAP -> 12L
                        HapticFeedbackType.LONG_PRESS -> 35L
                        HapticFeedbackType.DOCK_SNAP -> 8L
                    }
                    vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(15L)
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacksAndMessages(null)
        stopAuraAnimation()
        dockAnimator?.cancel()
        dockAnimator = null
        recycleVelocityTracker()
        windowManager = null
        windowParams = null
        super.onDetachedFromWindow()
    }
}
