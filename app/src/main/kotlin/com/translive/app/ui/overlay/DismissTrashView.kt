package com.translive.app.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.AttributeSet
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * Phase O1: Modern Floating Dismiss Trash Zone View.
 * Renders at the bottom of the screen when dragging the floating bubble.
 * Provides visual scale-up and magnetic glow when bubble approaches within the snap threshold.
 */
class DismissTrashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    val baseRadiusPx = 36f * density
    val totalSizePx = ((baseRadiusPx * 2) + (32f * density)).toInt()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#E6200A0A") // Deep translucent red
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#80EF4444") // Coral red border
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = Color.parseColor("#40EF4444")
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FCA5A5")
    }

    private val iconPath = Path()
    private val boundsRect = RectF()

    private var currentScale = 1.0f
    private var isHovered = false
    private var entranceAnimator: ValueAnimator? = null
    private var hoverAnimator: ValueAnimator? = null

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
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(totalSizePx, totalSizePx)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val radius = baseRadiusPx * currentScale

        // Draw Outer Aura
        if (isHovered) {
            canvas.drawCircle(cx, cy, radius + (6f * density), glowPaint)
        }

        // Draw Core Pill
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius - (strokePaint.strokeWidth / 2f), strokePaint)

        // Draw Close "✕" Glyph
        val iconSpan = (12f * density) * currentScale
        canvas.drawLine(cx - iconSpan, cy - iconSpan, cx + iconSpan, cy + iconSpan, iconPaint)
        canvas.drawLine(cx + iconSpan, cy - iconSpan, cx - iconSpan, cy + iconSpan, iconPaint)
    }

    fun animateEntrance() {
        alpha = 0f
        scaleX = 0.6f
        scaleY = 0.6f
        visibility = VISIBLE

        entranceAnimator?.cancel()
        entranceAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240L
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                alpha = f
                scaleX = 0.6f + (0.4f * f)
                scaleY = 0.6f + (0.4f * f)
            }
            start()
        }
    }

    fun animateExit(onComplete: (() -> Unit)? = null) {
        entranceAnimator?.cancel()
        hoverAnimator?.cancel()

        animate()
            .alpha(0f)
            .scaleX(0.5f)
            .scaleY(0.5f)
            .setDuration(180L)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = GONE
                    onComplete?.invoke()
                }
            })
            .start()
    }

    fun setMagneticHover(hovered: Boolean, scaleFactor: Float) {
        if (isHovered != hovered) {
            isHovered = hovered
            if (hovered) {
                performHapticTick()
                strokePaint.color = Color.parseColor("#FFEF4444")
                fillPaint.color = Color.parseColor("#F03B0F0F")
                iconPaint.color = Color.WHITE
            } else {
                strokePaint.color = Color.parseColor("#80EF4444")
                fillPaint.color = Color.parseColor("#E6200A0A")
                iconPaint.color = Color.parseColor("#FCA5A5")
            }
        }

        hoverAnimator?.cancel()
        val targetScale = if (hovered) scaleFactor.coerceIn(1.0f, 1.4f) else 1.0f
        currentScale = targetScale
        invalidate()
    }

    private fun performHapticTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(25L, 160))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(25L)
            }
        } catch (_: Exception) {}
    }
}
