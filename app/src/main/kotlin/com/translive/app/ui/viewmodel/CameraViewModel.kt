package com.translive.app.ui.viewmodel

import android.graphics.*
import android.text.TextPaint
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.model.Language
import com.translive.app.engine.CameraTranslateEngine
import com.translive.app.engine.OcrLine
import com.translive.app.engine.OcrResult
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
import kotlin.math.abs
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

enum class CaptureStatus { IDLE, PROCESSING, READY, EMPTY, ERROR }

data class CameraUiState(
    val mode: CameraMode = CameraMode.LIVE,
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.RUSSIAN,
    val liveBlocks: List<TranslatedBlock> = emptyList(),
    val captureStatus: CaptureStatus = CaptureStatus.IDLE,
    val captureMessage: String? = null,
    val capturedBitmap: Bitmap? = null,
    /** Bitmap with translations painted on top */
    val paintedBitmap: Bitmap? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val hasCameraPermission: Boolean = false,
    /** ML Kit model download status */
    val isNmtReady: Boolean = false,
    val isNmtDownloading: Boolean = false,
    val nmtError: String? = null
) {
    val isCaptureProcessing: Boolean get() = captureStatus == CaptureStatus.PROCESSING
}

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

    /** Live OCR line tracks keep overlays attached to the same visual row between frames. */
    private val liveTracks = mutableListOf<LiveTextTrack>()
    private var nextLiveTrackId = 0
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
            val ok = cameraTranslateEngine.prepare(state.sourceLanguage.code, state.targetLanguage.code)
            if (!ok) {
                _uiState.update { it.copy(nmtError = "Модель перевода недоступна. Нужен интернет для первой загрузки.") }
            } else {
                _uiState.update { it.copy(nmtError = null) }
            }
        }
    }

    fun setPermissionGranted(granted: Boolean) {
        _uiState.update { it.copy(hasCameraPermission = granted) }
    }

    fun setSourceLanguage(lang: Language) {
        _uiState.update { it.copy(sourceLanguage = lang) }
        resetModeVisuals()
        prepareNmt()
    }

    fun setTargetLanguage(lang: Language) {
        _uiState.update { it.copy(targetLanguage = lang) }
        resetModeVisuals()
        prepareNmt()
    }

    fun swapLanguages() {
        _uiState.update {
            it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
        }
        resetModeVisuals()
        prepareNmt()
    }

    private fun resetModeVisuals() {
        translateJob?.cancel()
        clearLiveTracks()
        _uiState.update {
            it.copy(
                mode = CameraMode.LIVE,
                liveBlocks = emptyList(),
                capturedBitmap = null,
                paintedBitmap = null,
                captureStatus = CaptureStatus.IDLE,
                captureMessage = null,
                imageWidth = 0,
                imageHeight = 0
            )
        }
    }

    private fun prepareNmt() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val ok = cameraTranslateEngine.prepare(state.sourceLanguage.code, state.targetLanguage.code)
            if (!ok) {
                _uiState.update { it.copy(nmtError = "Модель перевода не скачана") }
            } else {
                _uiState.update { it.copy(nmtError = null) }
            }
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
                val rawLines = extractLiveLines(ocrResult)

                if (rawLines.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            liveBlocks = emptyList(),
                            imageWidth = ocrResult.imageWidth,
                            imageHeight = ocrResult.imageHeight
                        )
                    }
                    return@launch
                }

                val smoothedLines = smoothLiveLines(rawLines)
                val translatedBlocks = translateLiveLines(smoothedLines)

                _uiState.update {
                    it.copy(
                        liveBlocks = translatedBlocks,
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

    private fun extractLiveLines(ocrResult: OcrResult): List<OcrLine> =
        ocrResult.blocks.flatMap { block ->
            block.lines.filter { it.boundingBox.width() > 30 && it.boundingBox.height() > 10 }
        }.sortedWith(compareBy({ it.boundingBox.top }, { it.boundingBox.left }))

    private fun smoothLiveLines(lines: List<OcrLine>): List<OcrLine> {
        val currentFrame = frameCounter
        val matchedTrackIds = mutableSetOf<Int>()
        val smoothedLines = lines.map { line ->
            val track = findLiveTrack(line.boundingBox, matchedTrackIds)
                ?: LiveTextTrack(
                    id = nextLiveTrackId++,
                    box = SmoothedRect(line.boundingBox),
                    lastSeenFrame = currentFrame
                ).also { liveTracks.add(it) }

            matchedTrackIds.add(track.id)
            track.lastSeenFrame = currentFrame
            track.box.update(line.boundingBox)
            line.copy(boundingBox = track.box.get())
        }

        liveTracks.removeAll { currentFrame - it.lastSeenFrame > LIVE_TRACK_TTL_FRAMES }
        return smoothedLines
    }

    private fun findLiveTrack(box: Rect, matchedTrackIds: Set<Int>): LiveTextTrack? {
        var bestTrack: LiveTextTrack? = null
        var bestScore = Float.MAX_VALUE

        for (track in liveTracks) {
            if (track.id in matchedTrackIds) continue

            val trackBox = track.box.get()
            val dx = centerX(box) - centerX(trackBox)
            val dy = centerY(box) - centerY(trackBox)
            val maxDx = max(box.width(), trackBox.width()) * 1.15f + 24f
            val maxDy = max(box.height(), trackBox.height()) * 1.8f + 18f
            if (abs(dx) > maxDx || abs(dy) > maxDy) continue

            val score = dx * dx + dy * dy * 2f
            if (score < bestScore) {
                bestScore = score
                bestTrack = track
            }
        }

        return bestTrack
    }

    private fun clearLiveTracks() {
        liveTracks.clear()
        nextLiveTrackId = 0
    }

    private suspend fun translateLiveLines(lines: List<OcrLine>): List<TranslatedBlock> {
        if (!cameraTranslateEngine.isReady.value) {
            return lines.map { line ->
                TranslatedBlock(line.text, "", line.boundingBox)
            }
        }

        val translations = cameraTranslateEngine.translateLines(lines.map { it.text })
        return lines.mapIndexed { i, line ->
            TranslatedBlock(
                originalText = line.text,
                translatedText = translations.getOrElse(i) { line.text },
                boundingBox = line.boundingBox
            )
        }
    }

    /**
     * Capture: freeze bitmap → OCR → HY-MT quality translate → paint on bitmap.
     */
    fun capturePreview(bitmap: Bitmap?) {
        if (bitmap == null) {
            _uiState.update { it.copy(captureMessage = "Кадр камеры пока недоступен") }
            return
        }

        val workBitmap = prepareCaptureBitmap(bitmap)
        val state = _uiState.value
        enterCaptureMode(workBitmap)

        translateJob = viewModelScope.launch(Dispatchers.IO) {
            processCaptureBitmap(
                bitmap = workBitmap,
                sourceLanguage = state.sourceLanguage,
                targetLanguage = state.targetLanguage
            )
        }
    }

    private fun prepareCaptureBitmap(bitmap: Bitmap): Bitmap {
        val maxSize = 1920
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxSize) return bitmap

        val scale = maxSize.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true
        )
    }

    private fun enterCaptureMode(workBitmap: Bitmap) {
        translateJob?.cancel()
        clearLiveTracks()
        _uiState.update {
            it.copy(
                mode = CameraMode.CAPTURE,
                capturedBitmap = workBitmap,
                paintedBitmap = workBitmap,
                liveBlocks = emptyList(),
                captureStatus = CaptureStatus.PROCESSING,
                captureMessage = "Ищу текст на снимке"
            )
        }
    }

    private suspend fun processCaptureBitmap(
        bitmap: Bitmap,
        sourceLanguage: Language,
        targetLanguage: Language
    ) {
        try {
            val ocrResult = ocrEngine.recognize(bitmap, sourceLanguage.code)

            if (ocrResult.blocks.isEmpty()) {
                _uiState.update {
                    it.copy(
                        captureStatus = CaptureStatus.EMPTY,
                        captureMessage = "Текст не найден"
                    )
                }
                return
            }

            val allLines = extractCaptureLines(ocrResult)

            if (allLines.isEmpty()) {
                _uiState.update {
                    it.copy(
                        captureStatus = CaptureStatus.EMPTY,
                        captureMessage = "Подходящие строки не найдены"
                    )
                }
                return
            }

            _uiState.update { it.copy(captureMessage = "Перевожу найденные строки") }

            val translatedParts = translateCaptureLines(allLines, sourceLanguage, targetLanguage)
            val painted = paintOnBitmap(bitmap, allLines, translatedParts)

            _uiState.update {
                it.copy(
                    paintedBitmap = painted,
                    captureStatus = CaptureStatus.READY,
                    captureMessage = null
                )
            }
        } catch (e: Exception) {
            Log.e("CameraVM", "Capture error: ${e.message}", e)
            _uiState.update {
                it.copy(
                    captureStatus = CaptureStatus.ERROR,
                    captureMessage = "Ошибка обработки снимка"
                )
            }
        }
    }

    private fun extractCaptureLines(ocrResult: OcrResult): List<PaintLine> =
        ocrResult.blocks.flatMap { block ->
            block.lines
                .filter { it.boundingBox.width() > 20 && it.boundingBox.height() > 8 }
                .map { PaintLine(it.text, it.boundingBox) }
        }

    private suspend fun translateCaptureLines(
        lines: List<PaintLine>,
        sourceLanguage: Language,
        targetLanguage: Language
    ): List<String> {
        val texts = lines.map { it.text }
        return if (translationEngine.isLoaded) {
            val separator = "\n---\n"
            val allText = texts.joinToString(separator)
            try {
                val translated = translationEngine.translateSafe(allText, sourceLanguage, targetLanguage)
                translated.split("---").map { it.trim() }
            } catch (e: Exception) {
                Log.e("CameraVM", "HY-MT failed, falling back to ML Kit: ${e.message}")
                if (cameraTranslateEngine.isReady.value) cameraTranslateEngine.translateLines(texts) else emptyList()
            }
        } else if (cameraTranslateEngine.isReady.value) {
            cameraTranslateEngine.translateLines(texts)
        } else {
            emptyList()
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
        clearLiveTracks()
        _uiState.update {
            it.copy(
                mode = CameraMode.LIVE,
                capturedBitmap = null,
                paintedBitmap = null,
                liveBlocks = emptyList(),
                captureStatus = CaptureStatus.IDLE,
                captureMessage = null
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraTranslateEngine.release()
    }
}

private const val LIVE_TRACK_TTL_FRAMES = 5

private data class LiveTextTrack(
    val id: Int,
    val box: SmoothedRect,
    var lastSeenFrame: Int
)

private fun centerX(rect: Rect): Float = (rect.left + rect.right) / 2f

private fun centerY(rect: Rect): Float = (rect.top + rect.bottom) / 2f

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
