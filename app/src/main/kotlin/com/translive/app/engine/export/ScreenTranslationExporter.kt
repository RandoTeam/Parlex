package com.translive.app.engine.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.translive.app.ui.overlay.ArTranslatedBox
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Result representation for screen export operation.
 */
sealed interface ScreenExportResult {
    data class Success(
        val uri: Uri,
        val relativePath: String,
        val byteCount: Long
    ) : ScreenExportResult

    data class Failure(
        val cause: Throwable,
        val message: String
    ) : ScreenExportResult
}

/**
 * High-performance exporter for AR Screen Translations.
 *
 * Composites high-contrast translated pill cards directly over the captured
 * screenshot and saves to device storage (Pictures/Parlex) via Scoped Storage.
 */
@Singleton
class ScreenTranslationExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ScreenTranslationExporter"
        private const val MIME_PNG = "image/png"
        private const val SUBFOLDER = "Pictures/Parlex"
    }

    /**
     * Executes the full export pipeline: renders composite bitmap and saves to MediaStore/storage.
     */
    suspend fun export(
        sourceBitmap: Bitmap,
        boxes: List<ArTranslatedBox>,
        sourceLangCode: String = "auto",
        targetLangCode: String = "ru"
    ): ScreenExportResult = withContext(Dispatchers.Default) {
        try {
            val compositeBitmap = renderComposite(sourceBitmap, boxes)
            withContext(Dispatchers.IO) {
                saveToStorage(compositeBitmap, sourceLangCode, targetLangCode)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Screen export pipeline failed", e)
            ScreenExportResult.Failure(e, e.message ?: "Unknown export failure")
        }
    }

    /**
     * Renders translated pill overlays on top of the source bitmap into a new mutable Bitmap.
     */
    fun renderComposite(
        sourceBitmap: Bitmap,
        boxes: List<ArTranslatedBox>
    ): Bitmap {
        val width = sourceBitmap.width
        val height = sourceBitmap.height
        require(width > 0 && height > 0) { "Source bitmap dimensions must be positive" }

        val composite = if (sourceBitmap.config == Bitmap.Config.HARDWARE || !sourceBitmap.isMutable) {
            sourceBitmap.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            Bitmap.createBitmap(sourceBitmap)
        }

        val canvas = Canvas(composite)
        val density = context.resources.displayMetrics.density

        val padHorizontal = 6f * density
        val padVertical = 4f * density
        val minPillCornerRadius = 4f * density
        val maxPillCornerRadius = 12f * density

        val pillFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F212121E")
            style = Paint.Style.FILL
        }

        val pillStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#6680D8FF")
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * density
        }

        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        val minTextSizePx = 11f * density
        val maxTextSizePx = 22f * density
        val maxCanvasW = width.toFloat()
        val maxCanvasH = height.toFloat()

        for (box in boxes) {
            val srcRect = box.boundingBox
            if (srcRect.width() <= 0 || srcRect.height() <= 0) continue

            val text = box.translatedText.ifBlank { box.rawText }
            if (text.isBlank()) continue

            val srcWidth = srcRect.width().toFloat()
            val srcHeight = srcRect.height().toFloat()

            var candidateTextSize = (srcHeight * 0.72f).coerceIn(minTextSizePx, maxTextSizePx)
            textPaint.textSize = candidateTextSize

            var targetWidth = max(srcWidth, 48f * density)
            var staticLayout = buildStaticLayout(text, textPaint, targetWidth.toInt())

            var attempts = 0
            while (attempts < 4 && candidateTextSize > minTextSizePx && staticLayout.height > srcHeight * 2.2f) {
                candidateTextSize = max(minTextSizePx, candidateTextSize * 0.85f)
                textPaint.textSize = candidateTextSize
                targetWidth = min(maxCanvasW - (32f * density), max(targetWidth, staticLayout.maxLineWidth + padHorizontal * 2))
                staticLayout = buildStaticLayout(text, textPaint, targetWidth.toInt())
                attempts++
            }

            val layoutW = staticLayout.maxLineWidth
            val layoutH = staticLayout.height.toFloat()

            val finalWidth = max(srcWidth, layoutW + (padHorizontal * 2))
            val finalHeight = max(srcHeight, layoutH + (padVertical * 2))

            val centerX = srcRect.centerX().toFloat()
            val centerY = srcRect.centerY().toFloat()

            val left = (centerX - finalWidth / 2f).coerceIn(4f * density, maxCanvasW - finalWidth - 4f * density)
            val top = (centerY - finalHeight / 2f).coerceIn(4f * density, maxCanvasH - finalHeight - 4f * density)
            val right = left + finalWidth
            val bottom = top + finalHeight

            val pillRect = RectF(left, top, right, bottom)
            val cornerRadius = (finalHeight / 2f).coerceIn(minPillCornerRadius, maxPillCornerRadius)

            // Draw pill fill and stroke
            canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, pillFillPaint)
            canvas.drawRoundRect(pillRect, cornerRadius, cornerRadius, pillStrokePaint)

            // Draw text
            val textOffsetX = left + ((finalWidth - layoutW) / 2f)
            val textOffsetY = top + ((finalHeight - layoutH) / 2f)
            canvas.save()
            canvas.translate(textOffsetX, textOffsetY)
            staticLayout.draw(canvas)
            canvas.restore()
        }

        return composite
    }

    private fun buildStaticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout {
        val safeWidth = max(1, width)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            StaticLayout.Builder.obtain(text, 0, text.length, paint, safeWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.05f)
                .setIncludePad(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            StaticLayout(text, paint, safeWidth, Layout.Alignment.ALIGN_NORMAL, 1.05f, 0f, false)
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

    private fun saveToStorage(
        bitmap: Bitmap,
        sourceLangCode: String,
        targetLangCode: String
    ): ScreenExportResult {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "parlex_${sourceLangCode}_${targetLangCode}_$timestamp.png"

        // MediaStore Scoped Storage (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentResolver: ContentResolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
                put(MediaStore.Images.Media.RELATIVE_PATH, SUBFOLDER)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            var uri: Uri? = null
            try {
                uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    var bytesWritten = 0L
                    contentResolver.openOutputStream(uri, "w")?.use { outputStream ->
                        val countingStream = CountingOutputStream(outputStream)
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, countingStream)
                        countingStream.flush()
                        bytesWritten = countingStream.bytesWritten
                    }

                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    contentResolver.update(uri, contentValues, null, null)

                    return ScreenExportResult.Success(
                        uri = uri,
                        relativePath = "$SUBFOLDER/$fileName",
                        byteCount = bytesWritten
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore insert failed, using direct storage fallback", e)
                if (uri != null) {
                    try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
                }
            }
        }

        // Direct File Fallback (API 26-28)
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val parlexDir = File(picturesDir, "Parlex").apply { if (!exists()) mkdirs() }
        val targetFile = File(parlexDir, fileName)
        var bytesWritten = 0L

        FileOutputStream(targetFile).use { fos ->
            val countingStream = CountingOutputStream(fos)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, countingStream)
            countingStream.flush()
            bytesWritten = countingStream.bytesWritten
        }

        val fileUri = Uri.fromFile(targetFile)
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(targetFile.absolutePath),
                arrayOf(MIME_PNG),
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "MediaScanner broadcast failed", e)
        }

        return ScreenExportResult.Success(
            uri = fileUri,
            relativePath = targetFile.absolutePath,
            byteCount = bytesWritten
        )
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}
