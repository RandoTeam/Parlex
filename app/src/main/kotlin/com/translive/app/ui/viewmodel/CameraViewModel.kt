package com.translive.app.ui.viewmodel

import android.graphics.*
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
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

private data class CaptureOcrPass(
    val result: OcrResult,
    val sourceLanguage: Language
)

enum class CameraMode { LIVE, CAPTURE }

enum class CaptureStatus { IDLE, PROCESSING, READY, EMPTY, ERROR }

data class CameraUiState(
    val mode: CameraMode = CameraMode.LIVE,
    val sourceLanguage: Language = Language.RUSSIAN,
    val targetLanguage: Language = Language.ENGLISH,
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
    private val liveTranslationCache = object : LinkedHashMap<String, String>(
        LIVE_TRANSLATION_CACHE_LIMIT,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > LIVE_TRANSLATION_CACHE_LIMIT
    }
    private var frameCounter = 0
    private var lastLiveFrameStartedAtMs = 0L

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
        clearLiveSession()
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

    fun startFullResolutionCapture() {
        _uiState.update {
            it.copy(
                captureStatus = CaptureStatus.PROCESSING,
                captureMessage = "Снимаю кадр"
            )
        }
    }

    fun failFullResolutionCapture(message: String = "Не удалось снять кадр") {
        _uiState.update {
            it.copy(
                captureStatus = CaptureStatus.ERROR,
                captureMessage = message
            )
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    fun captureImage(imageProxy: androidx.camera.core.ImageProxy) {
        if (_uiState.value.mode != CameraMode.LIVE) {
            imageProxy.close()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = try {
                ocrEngine.imageProxyToUprightBitmap(imageProxy)
            } catch (e: Exception) {
                Log.e("CameraVM", "ImageCapture conversion failed: ${e.message}", e)
                null
            } finally {
                imageProxy.close()
            }

            captureBitmap(bitmap)
        }
    }

    /**
     * Live mode: OCR + ML Kit translate → show translated overlays.
     */
    @androidx.camera.core.ExperimentalGetImage
    fun processLiveFrame(imageProxy: androidx.camera.core.ImageProxy) {
        if (_uiState.value.mode != CameraMode.LIVE) {
            imageProxy.close()
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        if (isLiveProcessing || nowMs - lastLiveFrameStartedAtMs < LIVE_FRAME_INTERVAL_MS) {
            imageProxy.close()
            return
        }

        lastLiveFrameStartedAtMs = nowMs
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

    private fun clearLiveSession() {
        liveTracks.clear()
        liveTranslationCache.clear()
        nextLiveTrackId = 0
        lastLiveFrameStartedAtMs = 0L
    }

    private suspend fun translateLiveLines(lines: List<OcrLine>): List<TranslatedBlock> {
        if (!cameraTranslateEngine.isReady.value) {
            return lines.map { line ->
                TranslatedBlock(line.text, "", line.boundingBox)
            }
        }

        val state = _uiState.value
        val cacheKeys = lines.map {
            liveTranslationCacheKey(state.sourceLanguage.code, state.targetLanguage.code, it.text)
        }
        val translationsByKey = mutableMapOf<String, String>()
        val missingTexts = mutableListOf<String>()
        val missingKeys = mutableListOf<String>()

        cacheKeys.forEachIndexed { index, key ->
            val cached = liveTranslationCache[key]
            if (cached != null) {
                translationsByKey[key] = cached
            } else if (key !in missingKeys) {
                missingKeys.add(key)
                missingTexts.add(lines[index].text)
            }
        }

        if (missingTexts.isNotEmpty()) {
            val freshTranslations = cameraTranslateEngine.translateLines(missingTexts)
            missingKeys.forEachIndexed { index, key ->
                val translated = freshTranslations.getOrElse(index) { missingTexts[index] }
                liveTranslationCache[key] = translated
                translationsByKey[key] = translated
            }
        }

        return lines.mapIndexed { i, line ->
            TranslatedBlock(
                originalText = line.text,
                translatedText = translationsByKey[cacheKeys[i]] ?: line.text,
                boundingBox = line.boundingBox
            )
        }
    }

    /**
     * Capture: full-resolution bitmap -> OCR -> HY-MT quality translate -> paint on bitmap.
     */
    private fun captureBitmap(bitmap: Bitmap?) {
        if (bitmap == null) {
            failFullResolutionCapture("Кадр камеры пока недоступен")
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
        clearLiveSession()
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
            val captureOcr = recognizeCaptureBitmap(bitmap, sourceLanguage)
            val ocrResult = captureOcr.result
            val effectiveSourceLanguage = captureOcr.sourceLanguage

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

            val translatedParts = translateCaptureLines(allLines, effectiveSourceLanguage, targetLanguage)
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

    private suspend fun recognizeCaptureBitmap(
        bitmap: Bitmap,
        sourceLanguage: Language
    ): CaptureOcrPass {
        val primaryResult = ocrEngine.recognize(bitmap, sourceLanguage.code)
        if (hasUsableCaptureText(primaryResult)) {
            return CaptureOcrPass(primaryResult, sourceLanguage)
        }

        val triedCodes = mutableSetOf(sourceLanguage.code)
        for (fallbackCode in captureOcrFallbackCodes(sourceLanguage)) {
            if (!triedCodes.add(fallbackCode)) continue

            val fallbackResult = ocrEngine.recognize(bitmap, fallbackCode)
            if (hasUsableCaptureText(fallbackResult) && matchesExpectedScript(fallbackResult, fallbackCode)) {
                val fallbackLanguage = Language.fromCode(fallbackCode) ?: sourceLanguage
                Log.i("CameraVM", "Capture OCR fallback: ${sourceLanguage.code} -> $fallbackCode")
                return CaptureOcrPass(fallbackResult, fallbackLanguage)
            }
        }

        return CaptureOcrPass(primaryResult, sourceLanguage)
    }

    private fun captureOcrFallbackCodes(sourceLanguage: Language): List<String> =
        when (sourceLanguage.code) {
            "ru" -> listOf("uk", "en")
            "uk" -> listOf("ru", "en")
            "en", "fr", "de", "es", "pt", "it", "nl", "pl", "cs",
            "tr", "vi", "id", "ms", "fil" -> listOf("ru", "uk")
            else -> emptyList()
        }

    private fun hasUsableCaptureText(result: OcrResult): Boolean =
        result.blocks.any { block ->
            block.lines.any { line ->
                line.text.isNotBlank() &&
                    line.boundingBox.width() > 20 &&
                    line.boundingBox.height() > 8
            }
        }

    private fun matchesExpectedScript(result: OcrResult, languageCode: String): Boolean {
        val text = result.blocks.joinToString(" ") { it.text }
        return when (languageCode) {
            "ru", "uk", "mn" -> text.any { it in '\u0400'..'\u04FF' }
            "en" -> text.any { it in 'A'..'Z' || it in 'a'..'z' }
            else -> true
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
        if (sourceLanguage.code == targetLanguage.code) return texts

        return if (translationEngine.isLoaded) {
            val translatedLines = mutableListOf<String>()
            try {
                for (text in texts) {
                    val translated = translationEngine.translateSafe(text, sourceLanguage, targetLanguage)
                    translatedLines.add(translated.trim().ifBlank { text })
                }
                translatedLines
            } catch (e: Exception) {
                Log.e("CameraVM", "HY-MT failed, falling back to ML Kit: ${e.message}")
                if (cameraTranslateEngine.isReady.value) cameraTranslateEngine.translateLines(texts) else texts
            }
        } else if (cameraTranslateEngine.isReady.value) {
            cameraTranslateEngine.translateLines(texts)
        } else {
            texts
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

            val box = clippedBox(line.box, result.width, result.height)
            if (box.width() <= 0 || box.height() <= 0) continue

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
            val padding = (boxH * 0.10f).coerceIn(2f, 6f)
            val maxTextWidth = (box.width().toFloat() - padding * 2f).coerceAtLeast(1f)
            val minTextSize = (boxH * 0.48f).coerceIn(8f, 18f)
            textPaint.textSize = (boxH * 0.78f).coerceIn(10f, 48f)
            textPaint.color = textColor

            // Shrink if too wide
            val singleLineText = translatedText.replace(Regex("\\s+"), " ")
            var measured = textPaint.measureText(singleLineText)
            while (measured > maxTextWidth && textPaint.textSize > minTextSize) {
                textPaint.textSize -= 1f
                measured = textPaint.measureText(singleLineText)
            }
            val displayText = TextUtils.ellipsize(
                singleLineText,
                textPaint,
                maxTextWidth,
                TextUtils.TruncateAt.END
            ).toString()

            // Draw text — vertically centered
            val x = box.left.toFloat() + padding
            val y = box.top.toFloat() + (boxH - textPaint.descent() - textPaint.ascent()) / 2f
            canvas.drawText(displayText, x, y, textPaint)
        }

        return result
    }

    private fun clippedBox(box: Rect, width: Int, height: Int): Rect {
        val left = box.left.coerceIn(0, width)
        val top = box.top.coerceIn(0, height)
        val right = box.right.coerceIn(left, width)
        val bottom = box.bottom.coerceIn(top, height)
        return Rect(left, top, right, bottom)
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
        clearLiveSession()
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
private const val LIVE_TRANSLATION_CACHE_LIMIT = 160
private const val LIVE_FRAME_INTERVAL_MS = 450L

private data class LiveTextTrack(
    val id: Int,
    val box: SmoothedRect,
    var lastSeenFrame: Int
)

private fun centerX(rect: Rect): Float = (rect.left + rect.right) / 2f

private fun centerY(rect: Rect): Float = (rect.top + rect.bottom) / 2f

private fun liveTranslationCacheKey(sourceCode: String, targetCode: String, text: String): String {
    val normalizedText = text.trim().replace(Regex("\\s+"), " ")
    return "$sourceCode>$targetCode:$normalizedText"
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
