package com.translive.app.engine

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.max
import kotlin.math.roundToInt

/** PP-OCR mobile tensor preprocessing; no network or model state. */
object PpOcrPreprocessor {
    data class ImageTensor(val values: FloatArray, val shape: IntArray, val scale: Float)

    /** Resize to a detector side limit and return normalized BGR NCHW tensor. */
    fun detector(bitmap: Bitmap, limitSide: Int = 960): ImageTensor {
        val scale = minOf(1f, limitSide.toFloat() / max(bitmap.width, bitmap.height).toFloat())
        val width = max(32, (bitmap.width * scale).roundToInt())
        val height = max(32, (bitmap.height * scale).roundToInt())
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        return ImageTensor(toChw(resized), intArrayOf(1, 3, height, width), scale)
            .also { if (resized !== bitmap) resized.recycle() }
    }

    /** Prepare one detected text line for a recognizer with height 48. */
    fun recognition(bitmap: Bitmap, quad: List<PointF>, height: Int = 48): ImageTensor {
        require(quad.size >= 4)
        val left = quad.minOf { it.x }.toInt().coerceIn(0, bitmap.width - 1)
        val top = quad.minOf { it.y }.toInt().coerceIn(0, bitmap.height - 1)
        val right = quad.maxOf { it.x }.toInt().coerceIn(left + 1, bitmap.width)
        val bottom = quad.maxOf { it.y }.toInt().coerceIn(top + 1, bitmap.height)
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        val width = max(16, (crop.width.toFloat() * height / crop.height).roundToInt())
        val resized = Bitmap.createScaledBitmap(crop, width, height, true)
        crop.recycle()
        return ImageTensor(toChw(resized), intArrayOf(1, 3, height, width), 1f)
            .also { resized.recycle() }
    }

    private fun toChw(bitmap: Bitmap): FloatArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val result = FloatArray(width * height * 3)
        val plane = width * height
        for (index in pixels.indices) {
            val pixel = pixels[index]
            // PP-OCR mobile models use BGR normalized to [0, 1] here; model
            // specific mean/scale can be applied at the package metadata layer.
            result[index] = (pixel and 0xff) / 255f
            result[plane + index] = ((pixel shr 8) and 0xff) / 255f
            result[plane * 2 + index] = ((pixel shr 16) and 0xff) / 255f
        }
        return result
    }
}
