package com.translive.app.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import kotlin.math.max
import kotlin.math.min

data class ArScanBox(
    val id: String,
    val rect: Rect,
    val primaryText: String,
    val secondaryText: String? = null,
    val tag: Any? = null
)

enum class ScanEffectState {
    IDLE,
    SCANNING,
    REVEALING,
    SETTLED
}

/**
 * High-performance hardware-accelerated scanning beam and AR bounding box reveal view
 * matching Google Circle to Search and Google Lens visual fidelity at 120Hz.
 */
class LensScanEffectView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var scanDurationMs: Long = 1400L
    var revealDurationMs: Long = 300L
    var isContinuousLoop: Boolean = false
    var onBoxClickListener: ((ArScanBox) -> Unit)? = null
    var onScanCompletedListener: (() -> Unit)? = null

    private var currentState: ScanEffectState = ScanEffectState.IDLE
    private var scanProgress: Float = 0f
    private var transitionProgress: Float = 0f
    private var scanAnimator: ValueAnimator? = null
    private var revealAnimator: ValueAnimator? = null

    private val density: Float = context.resources.displayMetrics.density
    private val beamHeight: Float = 140f * density
    private val razorCoreHeight: Float = 2.5f * density
    private val haloHeight: Float = 7f * density
    private val cornerRadius: Float = 8f * density
    private val cornerBracketLength: Float = 12f * density
    private val cornerBracketWidth: Float = 2f * density

    private val vignettePaint = Paint().apply {
        style = Paint.Style.FILL
    }

    private val beamTrailPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val razorCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val razorHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#9900F0FF")
    }

    private val boxBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#EE12121E")
    }

    private val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = Color.parseColor("#607C4DFF")
    }

    private val cornerBracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = cornerBracketWidth
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FF00F0FF")
    }

    private val primaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f * density, 0f, 2f * density, Color.parseColor("#B0000000"))
    }

    private val secondaryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC80D8FF")
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }

    private val shaderMatrix = Matrix()
    private var beamLinearGradient: LinearGradient? = null
    private var vignetteRadialGradient: RadialGradient? = null

    private val tempRectF = RectF()
    private val tempBoxBounds = RectF()
    private val tempPath = Path()

    private val activeBoxes = mutableListOf<ArScanBox>()

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return

        val beamColors = intArrayOf(
            Color.parseColor("#00000000"),
            Color.parseColor("#337C4DFF"),
            Color.parseColor("#8000E5FF"),
            Color.parseColor("#DD00F0FF")
        )
        val beamStops = floatArrayOf(0f, 0.45f, 0.85f, 1f)

        beamLinearGradient = LinearGradient(
            0f, 0f,
            0f, beamHeight,
            beamColors,
            beamStops,
            Shader.TileMode.CLAMP
        )
        beamTrailPaint.shader = beamLinearGradient

        val centerX = w * 0.5f
        val centerY = h * 0.5f
        val radius = max(w, h) * 0.85f
        val vignetteColors = intArrayOf(
            Color.parseColor("#20000000"),
            Color.parseColor("#600A0A14"),
            Color.parseColor("#9005050A")
        )
        val vignetteStops = floatArrayOf(0f, 0.6f, 1f)

        vignetteRadialGradient = RadialGradient(
            centerX, centerY, radius,
            vignetteColors,
            vignetteStops,
            Shader.TileMode.CLAMP
        )
        vignettePaint.shader = vignetteRadialGradient
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        if (viewWidth <= 0 || viewHeight <= 0 || currentState == ScanEffectState.IDLE) return

        val globalAlpha = when (currentState) {
            ScanEffectState.SCANNING -> 1f
            ScanEffectState.REVEALING -> 1f
            ScanEffectState.SETTLED -> 0.85f
            ScanEffectState.IDLE -> 0f
        }
        vignettePaint.alpha = (globalAlpha * 255).toInt()
        canvas.drawRect(0f, 0f, viewWidth, viewHeight, vignettePaint)

        if (currentState == ScanEffectState.SCANNING || currentState == ScanEffectState.REVEALING) {
            val beamAlphaMultiplier = if (currentState == ScanEffectState.REVEALING) {
                (1f - transitionProgress).coerceIn(0f, 1f)
            } else {
                1f
            }

            if (beamAlphaMultiplier > 0.001f) {
                val totalTravel = viewHeight + beamHeight
                val currentY = (scanProgress * totalTravel) - beamHeight

                beamLinearGradient?.let { shader ->
                    shaderMatrix.reset()
                    shaderMatrix.setTranslate(0f, currentY)
                    shader.setLocalMatrix(shaderMatrix)
                }

                beamTrailPaint.alpha = (beamAlphaMultiplier * 255).toInt()
                tempRectF.set(0f, currentY, viewWidth, currentY + beamHeight)
                canvas.drawRect(tempRectF, beamTrailPaint)

                val leadingY = currentY + beamHeight
                if (leadingY in 0f..viewHeight + haloHeight) {
                    razorHaloPaint.alpha = (beamAlphaMultiplier * 160).toInt()
                    tempRectF.set(
                        0f,
                        leadingY - (haloHeight * 0.5f),
                        viewWidth,
                        leadingY + (haloHeight * 0.5f)
                    )
                    canvas.drawRect(tempRectF, razorHaloPaint)

                    razorCorePaint.alpha = (beamAlphaMultiplier * 255).toInt()
                    tempRectF.set(
                        0f,
                        leadingY - (razorCoreHeight * 0.5f),
                        viewWidth,
                        leadingY + (razorCoreHeight * 0.5f)
                    )
                    canvas.drawRect(tempRectF, razorCorePaint)
                }
            }
        }

        if (currentState == ScanEffectState.REVEALING || currentState == ScanEffectState.SETTLED) {
            val boxAlpha = if (currentState == ScanEffectState.REVEALING) {
                transitionProgress.coerceIn(0f, 1f)
            } else {
                1f
            }

            val scale = if (currentState == ScanEffectState.REVEALING) {
                0.92f + (0.08f * transitionProgress)
            } else {
                1f
            }

            boxBackgroundPaint.alpha = (boxAlpha * 238).toInt()
            boxBorderPaint.alpha = (boxAlpha * 140).toInt()
            cornerBracketPaint.alpha = (boxAlpha * 255).toInt()
            primaryTextPaint.alpha = (boxAlpha * 255).toInt()
            secondaryTextPaint.alpha = (boxAlpha * 200).toInt()

            val padH = 6f * density
            val padV = 4f * density

            for (i in activeBoxes.indices) {
                val box = activeBoxes[i]
                val baseRect = box.rect

                val centerX = baseRect.centerX().toFloat()
                val centerY = baseRect.centerY().toFloat()
                val halfW = (baseRect.width() * 0.5f + padH) * scale
                val halfH = (baseRect.height() * 0.5f + padV) * scale

                tempBoxBounds.set(
                    centerX - halfW,
                    centerY - halfH,
                    centerX + halfW,
                    centerY + halfH
                )

                canvas.drawRoundRect(tempBoxBounds, cornerRadius, cornerRadius, boxBackgroundPaint)
                canvas.drawRoundRect(tempBoxBounds, cornerRadius, cornerRadius, boxBorderPaint)

                drawCornerBrackets(canvas, tempBoxBounds, cornerBracketLength * scale)

                val targetHeight = tempBoxBounds.height()
                val fontSize = (targetHeight * 0.65f).coerceIn(12f * density, 24f * density)
                primaryTextPaint.textSize = fontSize

                val textX = tempBoxBounds.left + (8f * density)
                val textY = tempBoxBounds.bottom - (tempBoxBounds.height() * 0.25f)
                canvas.drawText(box.primaryText, textX, textY, primaryTextPaint)

                box.secondaryText?.let { subText ->
                    secondaryTextPaint.textSize = fontSize * 0.7f
                    val subY = tempBoxBounds.top - (3f * density)
                    canvas.drawText(subText, textX, subY, secondaryTextPaint)
                }
            }
        }
    }

    private fun drawCornerBrackets(canvas: Canvas, r: RectF, len: Float) {
        val bracketLen = min(len, min(r.width(), r.height()) * 0.45f)
        tempPath.reset()

        tempPath.moveTo(r.left, r.top + bracketLen)
        tempPath.lineTo(r.left, r.top + cornerRadius)
        tempPath.quadTo(r.left, r.top, r.left + cornerRadius, r.top)
        tempPath.lineTo(r.left + bracketLen, r.top)

        tempPath.moveTo(r.right - bracketLen, r.top)
        tempPath.lineTo(r.right - cornerRadius, r.top)
        tempPath.quadTo(r.right, r.top, r.right, r.top + cornerRadius)
        tempPath.lineTo(r.right, r.top + bracketLen)

        tempPath.moveTo(r.right, r.bottom - bracketLen)
        tempPath.lineTo(r.right, r.bottom - cornerRadius)
        tempPath.quadTo(r.right, r.bottom, r.right - cornerRadius, r.bottom)
        tempPath.lineTo(r.right - bracketLen, r.bottom)

        tempPath.moveTo(r.left + bracketLen, r.bottom)
        tempPath.lineTo(r.left + cornerRadius, r.bottom)
        tempPath.quadTo(r.left, r.bottom, r.left, r.bottom - cornerRadius)
        tempPath.lineTo(r.left, r.bottom - bracketLen)

        canvas.drawPath(tempPath, cornerBracketPaint)
    }

    fun startScan() {
        stopAllAnimators()
        activeBoxes.clear()
        currentState = ScanEffectState.SCANNING
        scanProgress = 0f
        transitionProgress = 0f
        visibility = VISIBLE

        scanAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = scanDurationMs
            interpolator = AccelerateDecelerateInterpolator()
            repeatCount = if (isContinuousLoop) ValueAnimator.INFINITE else 0
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { animator ->
                scanProgress = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isContinuousLoop && currentState == ScanEffectState.SCANNING) {
                        onScanCompletedListener?.invoke()
                    }
                }
            })
            start()
        }
    }

    fun revealResults(boxes: List<ArScanBox>) {
        if (currentState == ScanEffectState.IDLE) {
            visibility = VISIBLE
        }

        activeBoxes.clear()
        activeBoxes.addAll(boxes)
        currentState = ScanEffectState.REVEALING

        revealAnimator?.cancel()
        revealAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = revealDurationMs
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { animator ->
                transitionProgress = animator.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    currentState = ScanEffectState.SETTLED
                    scanAnimator?.cancel()
                    scanAnimator = null
                    invalidate()
                }
            })
            start()
        }
    }

    fun reset() {
        stopAllAnimators()
        currentState = ScanEffectState.IDLE
        activeBoxes.clear()
        scanProgress = 0f
        transitionProgress = 0f
        visibility = GONE
        invalidate()
    }

    private fun stopAllAnimators() {
        scanAnimator?.cancel()
        scanAnimator = null
        revealAnimator?.cancel()
        revealAnimator = null
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentState != ScanEffectState.SETTLED && currentState != ScanEffectState.REVEALING) {
            return super.onTouchEvent(event)
        }

        if (event.action == MotionEvent.ACTION_UP) {
            val touchX = event.x
            val touchY = event.y

            val pad = 10f * density
            val clickedBox = activeBoxes.find { box ->
                touchX >= (box.rect.left - pad) && touchX <= (box.rect.right + pad) &&
                        touchY >= (box.rect.top - pad) && touchY <= (box.rect.bottom + pad)
            }

            if (clickedBox != null) {
                onBoxClickListener?.invoke(clickedBox)
                return true
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAllAnimators()
    }
}
