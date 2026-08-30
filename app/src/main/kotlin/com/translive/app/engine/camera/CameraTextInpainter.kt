package com.translive.app.engine.camera

import android.graphics.*
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.max

object CameraTextInpainter {

    /**
     * Paints translated paragraphs or lines in-place on the captured Bitmap using
     * gradient background pills, binary-search font auto-fitting, and contrast shadow typography.
     */
    fun renderInplaceTranslations(
        sourceBitmap: Bitmap,
        items: List<BilingualParagraph>
    ): Bitmap {
        if (items.isEmpty()) return sourceBitmap

        val result = sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            isSubpixelText = true
            isFakeBoldText = true
        }

        for (item in items) {
            val text = item.translatedText.replace(Regex("""\s+"""), " ").trim()
            if (text.isBlank()) continue

            val box = item.boundingBox
            if (box.width() <= 0 || box.height() <= 0) continue

            val bg = item.background ?: ColorSamplingAndLuminance.sampleBoxBackgroundAndContrast(sourceBitmap, box)

            // Padding calculation
            val padX = (box.width() * 0.04f).coerceIn(4f, 16f)
            val padY = (box.height() * 0.08f).coerceIn(3f, 12f)

            val paintRect = RectF(
                (box.left - padX).coerceAtLeast(0f),
                (box.top - padY).coerceAtLeast(0f),
                (box.right + padX).coerceAtMost(result.width.toFloat()),
                (box.bottom + padY).coerceAtMost(result.height.toFloat())
            )

            // Step 1: Draw Linear Gradient Pill (94% opacity)
            val topColor = Color.argb(240, Color.red(bg.topColor), Color.green(bg.topColor), Color.blue(bg.topColor))
            val botColor = Color.argb(240, Color.red(bg.bottomColor), Color.green(bg.bottomColor), Color.blue(bg.bottomColor))
            val gradient = LinearGradient(
                paintRect.left, paintRect.top,
                paintRect.left, paintRect.bottom,
                topColor, botColor,
                Shader.TileMode.CLAMP
            )
            bgPaint.shader = gradient
            val cornerRadius = (paintRect.height() * 0.14f).coerceIn(4f, 14f)
            canvas.drawRoundRect(paintRect, cornerRadius, cornerRadius, bgPaint)
            bgPaint.shader = null

            // Step 2: Optimal Text Layout Calculation
            val maxTextWidth = (paintRect.width() - padX * 1.5f).toInt().coerceAtLeast(1)
            val maxTextHeight = paintRect.height() - padY * 1.5f
            val baseTextSize = (paintRect.height() * 0.65f).coerceIn(10f, 60f)
            val minTextSize = (baseTextSize * 0.45f).coerceAtLeast(9f)

            textPaint.color = bg.primaryTextColor
            textPaint.textSize = baseTextSize
            textPaint.setShadowLayer(
                2.0f, 0f, 1.0f,
                if (bg.isDarkBackground) Color.argb(180, 0, 0, 0) else Color.argb(180, 255, 255, 255)
            )

            val maxLines = if (maxTextHeight >= baseTextSize * 2.1f) 4 else 2
            val layout = fitStaticLayout(
                text = text,
                paint = textPaint,
                targetWidth = maxTextWidth,
                targetHeight = maxTextHeight,
                maxLines = maxLines,
                minTextSize = minTextSize,
                maxTextSize = baseTextSize
            )

            // Step 3: Center Text Vertically & Draw with Clipping
            val textLeft = paintRect.left + padX * 0.75f
            val textTop = paintRect.top + padY * 0.75f + ((maxTextHeight - layout.height).coerceAtLeast(0f) / 2f)

            canvas.save()
            canvas.clipRect(paintRect)
            canvas.translate(textLeft, textTop)
            layout.draw(canvas)
            canvas.restore()
        }

        return result
    }

    private fun fitStaticLayout(
        text: String,
        paint: TextPaint,
        targetWidth: Int,
        targetHeight: Float,
        maxLines: Int,
        minTextSize: Float,
        maxTextSize: Float,
        step: Float = 0.5f
    ): StaticLayout {
        var low = minTextSize
        var high = maxTextSize
        var bestSize = minTextSize
        var bestLayout: StaticLayout? = null

        while (high - low >= step) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            val layout = createStaticLayout(text, paint, targetWidth, maxLines)

            if (layout.height <= targetHeight && layout.lineCount <= maxLines) {
                bestSize = mid
                bestLayout = layout
                low = mid + step
            } else {
                high = mid - step
            }
        }

        paint.textSize = bestSize
        return bestLayout ?: createStaticLayout(text, paint, targetWidth, maxLines)
    }

    private fun createStaticLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int
    ): StaticLayout {
        val safeWidth = width.coerceAtLeast(1)
        val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.95f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
            builder.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
        }

        return builder.build()
    }
}
