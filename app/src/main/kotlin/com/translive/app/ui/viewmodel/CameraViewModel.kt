package com.translive.app.ui.viewmodel

import android.graphics.*
import android.text.TextPaint
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.model.Language
import com.translive.app.engine.CameraTranslateEngine
import com.translive.app.engine.OcrEngine
import com.translive.app.engine.TranslationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.max

data class TranslatedBlock(
    val originalText: String,
    val translatedText: String,
    val boundingBox: Rect
)

/** A single OCR line ready for painting. */
private data class PaintLine(
    val text: String,
    val box: Rect
)

enum class CameraMode { LIVE, CAPTURE }

data class CameraUiState(
    val mode: CameraMode = CameraMode.LIVE,
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.RUSSIAN,
    val blocks: List<TranslatedBlock> = emptyList(),
    val isProcessing: Boolean = false,
    val capturedBitmap: Bitmap? = null,
    /** Bitmap with translations painted on top */
    val paintedBitmap: Bitmap? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val hasCameraPermission: Boolean = false,
    /** ML Kit model download status */
    val isNmtReady: Boolean = false,
    val isNmtDownloading: Boolean = false
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val ocrEngine: OcrEngine,
    private val translationEngine: TranslationEngine,
    private val cameraTranslateEngine: CameraTranslateEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null

    @Volatile
    private var isLiveProcessing = false

    /** Smoothed bounding boxes — EMA filter to prevent jitter */
    private val smoothedBoxes = mutableMapOf<Int, SmoothedRect>()
    private var frameCounter = 0

    init {
        // Observe ML Kit translation readiness
        viewModelScope.launch {
            cameraTranslateEngine.isReady.collect { ready ->
                _uiState.update { it.copy(isNmtReady = ready) }
            }
        }
        viewModelScope.launch {
            cameraTranslateEngine.isDownloading.collect { downloading ->
                _uiState.update { it.copy(isNmtDownloading = downloading) }
            }
        }
        // Prepare NMT for default language pair
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            cameraTranslateEngine.prepare(state.sourceLanguage.code, state.targetLanguage.code)
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
    }

    fun setSourceLanguage(lang: Language) {
        _uiState.update { it.copy(sourceLanguage = lang) }
        prepareNmt()
    }

    fun setTargetLanguage(lang: Language) {
        _uiState.update { it.copy(targetLanguage = lang) }
        prepareNmt()
    }

    fun swapLanguages() {
        _uiState.update {
            it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
        }
        prepareNmt()
    }

    private fun prepareNmt() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            cameraTranslateEngine.prepare(state.sourceLanguage.code, state.targetLanguage.code)
        }
    }

    /**
     * Live mode: OCR + ML Kit translate → show translated overlays.
     */
    @androidx.camera.core.ExperimentalGetImage
    fun processLiveFrame(imageProxy: androidx.camera.core.ImageProxy) {
        if (isLiveProcessing || _uiState.value.mode != CameraMode.LIVE) {
            imageProxy.close()
            return
        }
        isLiveProcessing = true
        frameCounter++
        Log.d("CameraVM", "processLiveFrame #$frameCounter, img=${imageProxy.width}x${imageProxy.height}")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val ocrResult = ocrEngine.recognize(imageProxy, state.sourceLanguage.code)

                val rawLines = ocrResult.blocks.flatMap { block ->
                    block.lines
                        .filter { it.boundingBox.width() > 30 && it.boundingBox.height() > 10 }
                        .map { line -> line }
                }

                if (rawLines.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            blocks = emptyList(),
                            imageWidth = ocrResult.imageWidth,
                            imageHeight = ocrResult.imageHeight
                        )
                    }
                    return@launch
                }

                // Apply EMA smoothing to bounding boxes
                val smoothedLines = rawLines.mapIndexed { idx, line ->
                    val key = idx
                    val smoothed = smoothedBoxes.getOrPut(key) { SmoothedRect(line.boundingBox) }
                    smoothed.update(line.boundingBox)
                    line.copy(boundingBox = smoothed.get())
                }
                // Clean up stale entries
                if (smoothedBoxes.size > rawLines.size + 5) {
                    val keysToRemove = smoothedBoxes.keys.filter { it >= rawLines.size + 5 }
                    keysToRemove.forEach { smoothedBoxes.remove(it) }
                }

                // Translate using ML Kit (fast ~20ms per line)
                val translatedBlocks = if (cameraTranslateEngine.isReady.value) {
                    val texts = smoothedLines.map { it.text }
                    val translations = cameraTranslateEngine.translateLines(texts)
                    smoothedLines.mapIndexed { i, line ->
                        TranslatedBlock(
                            originalText = line.text,
                            translatedText = translations.getOrElse(i) { line.text },
                            boundingBox = line.boundingBox
                        )
                    }
                } else {
                    // NMT not ready yet — show OCR boxes only
                    smoothedLines.map { line ->
                        TranslatedBlock(line.text, "", line.boundingBox)
                    }
                }

                _uiState.update {
                    it.copy(
                        blocks = translatedBlocks,
                        imageWidth = ocrResult.imageWidth,
                        imageHeight = ocrResult.imageHeight
                    )
                }
            } catch (e: Exception) {
                Log.e("CameraVM", "processLiveFrame error: ${e.message}", e)
            } finally {
                isLiveProcessing = false
            }
        }
    }

    /**
     * Capture: freeze bitmap → OCR → HY-MT quality translate → paint on bitmap.
     */
    fun capture(bitmap: Bitmap) {
        val maxSize = 1920
        val longest = max(bitmap.width, bitmap.height)
        val workBitmap = if (longest > maxSize) {
            val s = maxSize.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * s).toInt(), (bitmap.height * s).toInt(), true)
        } else bitmap

        _uiState.update {
            it.copy(
                mode = CameraMode.CAPTURE,
                capturedBitmap = workBitmap,
                paintedBitmap = workBitmap,
                blocks = emptyList(),
                isProcessing = true
            )
        }

        translateJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val ocrResult = ocrEngine.recognize(workBitmap, state.sourceLanguage.code)

                if (ocrResult.blocks.isEmpty()) {
                    _uiState.update { it.copy(isProcessing = false) }
                    return@launch
                }

                val allLines = ocrResult.blocks.flatMap { block ->
                    block.lines
                        .filter { it.boundingBox.width() > 20 && it.boundingBox.height() > 8 }
                        .map { PaintLine(it.text, it.boundingBox) }
                }

                if (allLines.isEmpty()) {
                    _uiState.update { it.copy(isProcessing = false) }
                    return@launch
                }

                // Capture mode: try HY-MT for quality, fallback to ML Kit
                val translatedParts = if (translationEngine.isLoaded) {
                    // Batch with HY-MT (quality)
                    val separator = "\n---\n"
                    val allText = allLines.joinToString(separator) { it.text }
                    try {
                        val translated = translationEngine.translateSafe(
                            allText, state.sourceLanguage, state.targetLanguage
                        )
                        translated.split("---").map { it.trim() }
                    } catch (e: Exception) {
                        Log.e("CameraVM", "HY-MT failed, falling back to ML Kit: ${e.message}")
                        // Fallback to ML Kit
                        if (cameraTranslateEngine.isReady.value) {
                            cameraTranslateEngine.translateLines(allLines.map { it.text })
                        } else emptyList()
                    }
                } else if (cameraTranslateEngine.isReady.value) {
                    // ML Kit fallback
                    cameraTranslateEngine.translateLines(allLines.map { it.text })
                } else emptyList()

                val painted = paintOnBitmap(workBitmap, allLines, translatedParts)

                _uiState.update {
                    it.copy(paintedBitmap = painted, isProcessing = false)
                }
            } catch (e: Exception) {
                Log.e("CameraVM", "Capture error: ${e.message}", e)
                _uiState.update { it.copy(isProcessing = false) }
            }
        }
    }

    /**
     * Paint translated text directly on bitmap.
     * Improved: blur original text area → paint semi-transparent bg → white text.
     */
    private fun paintOnBitmap(
        original: Bitmap,
        lines: List<PaintLine>,
        translations: List<String>
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
        }

        val textPaint = TextPaint().apply {
            isAntiAlias = true
            isFakeBoldText = true
        }

        for (i in lines.indices) {
            val line = lines[i]
            val translatedText = translations.getOrNull(i)
                ?.takeIf { it.isNotBlank() }
                ?: line.text

            val box = line.box

            // Sample background color around the box to choose contrast
            val bgColor = sampleBackgroundColor(original, box)
            val textColor = contrastColor(bgColor)

            // Draw semi-transparent background (matching surrounding color)
            bgPaint.color = Color.argb(210,
                Color.red(bgColor),
                Color.green(bgColor),
                Color.blue(bgColor)
            )
            canvas.drawRect(box, bgPaint)

            // Fit text size: ~80% of box height
            val boxH = box.height().toFloat()
            val boxW = box.width().toFloat()
            textPaint.textSize = (boxH * 0.78f).coerceIn(10f, 48f)
            textPaint.color = textColor

            // Shrink if too wide
            var measured = textPaint.measureText(translatedText)
            while (measured > boxW && textPaint.textSize > 8f) {
                textPaint.textSize -= 1f
                measured = textPaint.measureText(translatedText)
            }

            // Draw text — vertically centered
            val x = box.left.toFloat() + 2f
            val y = box.top.toFloat() + (boxH - textPaint.descent() - textPaint.ascent()) / 2f
            canvas.drawText(translatedText, x, y, textPaint)
        }

        return result
    }

    /** Sample the average color around a bounding box to match background. */
    private fun sampleBackgroundColor(bitmap: Bitmap, box: Rect): Int {
        val pad = 2
        var r = 0L; var g = 0L; var b = 0L; var count = 0

        // Sample pixels just outside the box edges
        for (x in (box.left - pad).coerceAtLeast(0)..(box.right + pad).coerceAtMost(bitmap.width - 1) step 3) {
            for (yOff in listOf(box.top - pad, box.bottom + pad)) {
                val y = yOff.coerceIn(0, bitmap.height - 1)
                val px = bitmap.getPixel(x, y)
                r += Color.red(px); g += Color.green(px); b += Color.blue(px); count++
            }
        }
        for (y in (box.top - pad).coerceAtLeast(0)..(box.bottom + pad).coerceAtMost(bitmap.height - 1) step 3) {
            for (xOff in listOf(box.left - pad, box.right + pad)) {
                val x = xOff.coerceIn(0, bitmap.width - 1)
                val px = bitmap.getPixel(x, y)
                r += Color.red(px); g += Color.green(px); b += Color.blue(px); count++
            }
        }

        return if (count > 0) {
            Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt())
        } else Color.DKGRAY
    }

    /** Return white or dark text depending on background luminance. */
    private fun contrastColor(bgColor: Int): Int {
        val lum = (0.299 * Color.red(bgColor) + 0.587 * Color.green(bgColor) + 0.114 * Color.blue(bgColor))
        return if (lum > 128) Color.BLACK else Color.WHITE
    }

    fun backToLive() {
        translateJob?.cancel()
        _uiState.update {
            it.copy(
                mode = CameraMode.LIVE,
                capturedBitmap = null,
                paintedBitmap = null,
                blocks = emptyList(),
                isProcessing = false
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraTranslateEngine.release()
    }
}

/**
 * Exponential Moving Average filter for Rect coordinates.
 * Prevents bounding box jitter between camera frames.
 */
private class SmoothedRect(initial: Rect, private val alpha: Float = 0.4f) {
    private var left = initial.left.toFloat()
    private var top = initial.top.toFloat()
    private var right = initial.right.toFloat()
    private var bottom = initial.bottom.toFloat()

    fun update(raw: Rect) {
        left = left + alpha * (raw.left - left)
        top = top + alpha * (raw.top - top)
        right = right + alpha * (raw.right - right)
        bottom = bottom + alpha * (raw.bottom - bottom)
    }

    fun get() = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
}
