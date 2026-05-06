package com.translive.app.ui.viewmodel

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.model.Language
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
    val hasCameraPermission: Boolean = false
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val ocrEngine: OcrEngine,
    private val translationEngine: TranslationEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null

    @Volatile
    private var isLiveProcessing = false

    fun setPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
    }

    fun setSourceLanguage(lang: Language) {
        _uiState.update { it.copy(sourceLanguage = lang) }
    }

    fun setTargetLanguage(lang: Language) {
        _uiState.update { it.copy(targetLanguage = lang) }
    }

    fun swapLanguages() {
        _uiState.update {
            it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
        }
    }

    /**
     * Live mode: OCR only — show line-level boxes, no translation.
     */
    @androidx.camera.core.ExperimentalGetImage
    fun processLiveFrame(imageProxy: androidx.camera.core.ImageProxy) {
        if (isLiveProcessing || _uiState.value.mode != CameraMode.LIVE) {
            imageProxy.close()
            return
        }
        isLiveProcessing = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val ocrResult = ocrEngine.recognize(imageProxy, state.sourceLanguage.code)

                val lineBlocks = ocrResult.blocks.flatMap { block ->
                    block.lines
                        .filter { it.boundingBox.width() > 30 && it.boundingBox.height() > 10 }
                        .map { line ->
                            TranslatedBlock(line.text, "", line.boundingBox)
                        }
                }

                _uiState.update {
                    it.copy(
                        blocks = lineBlocks,
                        imageWidth = ocrResult.imageWidth,
                        imageHeight = ocrResult.imageHeight
                    )
                }
            } catch (_: Exception) {
            } finally {
                isLiveProcessing = false
            }
        }
    }

    /**
     * Capture: freeze bitmap → OCR → batch translate → paint on bitmap.
     */
    fun capture(bitmap: Bitmap) {
        // Downscale if too large
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

                // Collect all lines
                val allLines = ocrResult.blocks.flatMap { block ->
                    block.lines
                        .filter { it.boundingBox.width() > 20 && it.boundingBox.height() > 8 }
                        .map { PaintLine(it.text, it.boundingBox) }
                }

                if (allLines.isEmpty()) {
                    _uiState.update { it.copy(isProcessing = false) }
                    return@launch
                }

                // BATCH: one LLM call for all lines
                val separator = "\n---\n"
                val allText = allLines.joinToString(separator) { it.text }

                val translated = if (translationEngine.isLoaded) {
                    try {
                        translationEngine.translateSafe(
                            allText, state.sourceLanguage, state.targetLanguage
                        )
                    } catch (e: Exception) {
                        Log.e("CameraVM", "Batch translate failed: ${e.message}")
                        ""
                    }
                } else ""

                val translatedParts = if (translated.isNotBlank()) {
                    translated.split("---").map { it.trim() }
                } else emptyList()

                // Paint on bitmap
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
     * Paint translated text directly on bitmap — like Google Translate.
     * For each line: solid dark background + white translated text.
     */
    private fun paintOnBitmap(
        original: Bitmap,
        lines: List<PaintLine>,
        translations: List<String>
    ): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)

        val bgPaint = Paint().apply {
            color = android.graphics.Color.argb(220, 30, 30, 30)
            style = Paint.Style.FILL
        }

        val textPaint = TextPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
        }

        for (i in lines.indices) {
            val line = lines[i]
            val translatedText = translations.getOrNull(i)
                ?.takeIf { it.isNotBlank() }
                ?: line.text

            val box = line.box

            // Draw background over original text
            canvas.drawRect(box, bgPaint)

            // Fit text size: start at ~75% of box height, shrink if too wide
            val boxH = box.height().toFloat()
            val boxW = box.width().toFloat()
            textPaint.textSize = (boxH * 0.75f).coerceIn(10f, 40f)

            var measured = textPaint.measureText(translatedText)
            while (measured > boxW && textPaint.textSize > 8f) {
                textPaint.textSize -= 1f
                measured = textPaint.measureText(translatedText)
            }

            // Draw text — vertically centered in the box
            val x = box.left.toFloat() + 2f
            val y = box.top.toFloat() + (boxH - textPaint.descent() - textPaint.ascent()) / 2f
            canvas.drawText(translatedText, x, y, textPaint)
        }

        return result
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
}
