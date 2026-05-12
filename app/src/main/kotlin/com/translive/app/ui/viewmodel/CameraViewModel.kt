package com.translive.app.ui.viewmodel

import android.content.Context
import android.content.pm.ApplicationInfo
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
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
    val id: String,
    val text: String,
    val box: Rect
)

/** OCR block translated as one context unit, while keeping per-line boxes for painting. */
private data class PaintBlock(
    val id: String,
    val lines: List<PaintLine>
)

private data class CaptureOcrPass(
    val result: OcrResult,
    val sourceLanguage: Language,
    val attempts: List<CaptureOcrAttempt>
)

private data class CaptureOcrAttempt(
    val language: Language,
    val result: OcrResult,
    val score: CaptureOcrScore
)

private data class CaptureOcrScore(
    val expectedScript: OcrTextScript,
    val lineCount: Int,
    val textLength: Int,
    val letterCount: Int,
    val expectedLetterCount: Int,
    val conflictingLetterCount: Int,
    val expectedScriptRatio: Float,
    val selectionScore: Float,
    val isStrongForExpectedScript: Boolean
)

private data class CaptureDebugLine(
    val id: String,
    val index: Int,
    val sourceText: String,
    val translatedText: String,
    val box: Rect
)

private data class CaptureDebugAttempt(
    val languageCode: String,
    val backend: String,
    val engineLanguage: String,
    val expectedScript: OcrTextScript,
    val lineCount: Int,
    val textLength: Int,
    val expectedScriptRatio: Float,
    val selectionScore: Float,
    val selected: Boolean
)

private data class CaptureDebugSnapshot(
    val createdAtMs: Long,
    val requestedSourceLanguage: Language,
    val effectiveSourceLanguage: Language,
    val targetLanguage: Language,
    val ocrBackend: String,
    val ocrLanguage: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val blocks: List<com.translive.app.engine.OcrBlock>,
    val attempts: List<CaptureDebugAttempt>,
    val lines: List<CaptureDebugLine>
)

private enum class OcrTextScript {
    CYRILLIC, LATIN, CJK, DEVANAGARI, ARABIC, HEBREW, OTHER
}

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
    @ApplicationContext private val appContext: Context,
    private val ocrEngine: OcrEngine,
    private val translationEngine: TranslationEngine,
    private val cameraTranslateEngine: CameraTranslateEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null
    @Volatile
    private var lastCaptureDebugSnapshot: CaptureDebugSnapshot? = null

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
        lastCaptureDebugSnapshot = null
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
        lastCaptureDebugSnapshot = null
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
                rememberCaptureDebugSnapshot(
                    sourceLanguage,
                    effectiveSourceLanguage,
                    targetLanguage,
                    ocrResult,
                    captureOcr.attempts,
                    emptyList(),
                    emptyList()
                )
                _uiState.update {
                    it.copy(
                        captureStatus = CaptureStatus.EMPTY,
                        captureMessage = "Текст не найден"
                    )
                }
                return
            }

            val captureBlocks = extractCaptureBlocks(ocrResult, effectiveSourceLanguage)
            val allLines = captureBlocks.flatMap { it.lines }

            if (allLines.isEmpty()) {
                rememberCaptureDebugSnapshot(
                    sourceLanguage,
                    effectiveSourceLanguage,
                    targetLanguage,
                    ocrResult,
                    captureOcr.attempts,
                    emptyList(),
                    emptyList()
                )
                _uiState.update {
                    it.copy(
                        captureStatus = CaptureStatus.EMPTY,
                        captureMessage = "Подходящие строки не найдены"
                    )
                }
                return
            }

            _uiState.update { it.copy(captureMessage = "Перевожу найденные строки") }

            val translatedParts = translateCaptureBlocks(captureBlocks, effectiveSourceLanguage, targetLanguage)
            rememberCaptureDebugSnapshot(
                sourceLanguage,
                effectiveSourceLanguage,
                targetLanguage,
                ocrResult,
                captureOcr.attempts,
                allLines,
                translatedParts
            )
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
        val attempts = mutableListOf(recognizeCaptureAttempt(bitmap, sourceLanguage))
        val triedCodes = mutableSetOf(sourceLanguage.code)
        for (fallbackCode in captureOcrFallbackCodes(sourceLanguage, attempts.first().score)) {
            if (!triedCodes.add(fallbackCode)) continue

            val fallbackLanguage = Language.fromCode(fallbackCode) ?: continue
            attempts += recognizeCaptureAttempt(bitmap, fallbackLanguage)
        }

        val selectedAttempt = selectCaptureOcrAttempt(sourceLanguage, attempts)
        if (selectedAttempt.language.code != sourceLanguage.code) {
            Log.i(
                "CameraVM",
                "Capture OCR fallback: ${sourceLanguage.code} -> ${selectedAttempt.language.code}, " +
                    "score=${selectedAttempt.score.selectionScore}, lines=${selectedAttempt.score.lineCount}"
            )
        }
        return CaptureOcrPass(selectedAttempt.result, selectedAttempt.language, attempts)
    }

    private suspend fun recognizeCaptureAttempt(
        bitmap: Bitmap,
        language: Language
    ): CaptureOcrAttempt {
        val result = ocrEngine.recognize(bitmap, language.code)
        return CaptureOcrAttempt(
            language = language,
            result = result,
            score = scoreCaptureOcrResult(result, language.code)
        )
    }

    private fun captureOcrFallbackCodes(
        sourceLanguage: Language,
        primaryScore: CaptureOcrScore
    ): List<String> {
        if (primaryScore.isStrongForExpectedScript) return emptyList()

        return when (expectedScriptForLanguage(sourceLanguage.code)) {
            OcrTextScript.CYRILLIC -> when (sourceLanguage.code) {
                "ru" -> listOf("uk", "en")
                "uk" -> listOf("ru", "en")
                else -> listOf("ru", "en")
            }
            OcrTextScript.LATIN -> listOf("ru", "uk")
            else -> emptyList()
        }
    }

    private fun selectCaptureOcrAttempt(
        requestedSourceLanguage: Language,
        attempts: List<CaptureOcrAttempt>
    ): CaptureOcrAttempt {
        val requestedScript = expectedScriptForLanguage(requestedSourceLanguage.code)
        return attempts.maxByOrNull { attempt ->
            var score = attempt.score.selectionScore
            if (attempt.language.code == requestedSourceLanguage.code) score += 24f
            if (attempt.score.expectedScript == requestedScript) score += 8f
            if (!attempt.score.isStrongForExpectedScript) score -= 20f
            score
        } ?: attempts.first()
    }

    private fun scoreCaptureOcrResult(result: OcrResult, languageCode: String): CaptureOcrScore {
        val expectedScript = expectedScriptForLanguage(languageCode)
        val lines = extractRawCaptureLines(result)
        val text = lines.joinToString(" ") { it.text }
        var letterCount = 0
        var expectedLetterCount = 0
        var conflictingLetterCount = 0

        for (char in text) {
            val script = scriptForChar(char)
            if (script == OcrTextScript.OTHER) continue

            letterCount++
            when {
                script == expectedScript -> expectedLetterCount++
                isConflictingScript(script, expectedScript) -> conflictingLetterCount++
            }
        }

        val textLength = text.count { !it.isWhitespace() }
        val expectedScriptRatio = if (letterCount > 0 && expectedScript != OcrTextScript.OTHER) {
            expectedLetterCount.toFloat() / letterCount.toFloat()
        } else if (textLength > 0 && expectedScript == OcrTextScript.OTHER) {
            1f
        } else {
            0f
        }
        val requiredExpectedLetters = if (textLength < 8) 2 else 6
        val isStrong = lines.isNotEmpty() &&
            textLength >= 3 &&
            (
                expectedScript == OcrTextScript.OTHER ||
                    (expectedLetterCount >= requiredExpectedLetters && expectedScriptRatio >= 0.45f)
                )
        val selectionScore = lines.size * 12f +
            textLength.coerceAtMost(240) * 0.55f +
            expectedLetterCount * 2.1f -
            conflictingLetterCount * 2.4f +
            expectedScriptRatio * 32f

        return CaptureOcrScore(
            expectedScript = expectedScript,
            lineCount = lines.size,
            textLength = textLength,
            letterCount = letterCount,
            expectedLetterCount = expectedLetterCount,
            conflictingLetterCount = conflictingLetterCount,
            expectedScriptRatio = expectedScriptRatio,
            selectionScore = selectionScore,
            isStrongForExpectedScript = isStrong
        )
    }

    private fun extractRawCaptureLines(ocrResult: OcrResult): List<PaintLine> =
        ocrResult.blocks.flatMapIndexed { blockIndex, block ->
            block.lines.mapIndexedNotNull { lineIndex, line ->
                if (line.boundingBox.width() <= 20 || line.boundingBox.height() <= 8) {
                    null
                } else {
                    PaintLine("b$blockIndex:l$lineIndex", line.text, line.boundingBox)
                }
            }
        }

    private fun extractCaptureBlocks(
        ocrResult: OcrResult,
        sourceLanguage: Language
    ): List<PaintBlock> {
        val expectedScript = expectedScriptForLanguage(sourceLanguage.code)
        return ocrResult.blocks.mapIndexedNotNull { blockIndex, block ->
            val rawLines = block.lines.mapIndexedNotNull { lineIndex, line ->
                if (line.boundingBox.width() <= 20 || line.boundingBox.height() <= 8) {
                    null
                } else {
                    PaintLine("b$blockIndex:l$lineIndex", line.text, line.boundingBox)
                }
            }
            val lines = if (expectedScript == OcrTextScript.OTHER) {
                rawLines
            } else {
                rawLines.filter { isPreferredCaptureLine(it.text, expectedScript) }
                    .ifEmpty { rawLines }
            }
            if (lines.isEmpty()) null else PaintBlock("b$blockIndex", lines)
        }
    }

    private fun isPreferredCaptureLine(text: String, expectedScript: OcrTextScript): Boolean {
        var letterCount = 0
        var expectedLetterCount = 0
        var conflictingLetterCount = 0

        for (char in text) {
            val script = scriptForChar(char)
            if (script == OcrTextScript.OTHER) continue

            letterCount++
            when {
                script == expectedScript -> expectedLetterCount++
                isConflictingScript(script, expectedScript) -> conflictingLetterCount++
            }
        }

        return letterCount == 0 || (expectedLetterCount > 0 && expectedLetterCount >= conflictingLetterCount)
    }

    private fun expectedScriptForLanguage(languageCode: String): OcrTextScript =
        when (languageCode) {
            "ru", "uk", "mn" -> OcrTextScript.CYRILLIC
            "en", "fr", "de", "es", "pt", "it", "nl", "pl", "cs",
            "tr", "vi", "id", "ms", "fil" -> OcrTextScript.LATIN
            "zh", "zh-Hant", "ja", "ko", "yue", "nan" -> OcrTextScript.CJK
            "hi", "mr", "gu" -> OcrTextScript.DEVANAGARI
            "ar", "fa", "ur", "ug" -> OcrTextScript.ARABIC
            "he" -> OcrTextScript.HEBREW
            else -> OcrTextScript.OTHER
        }

    private fun scriptForChar(char: Char): OcrTextScript {
        return when {
            char in '\u0400'..'\u052F' -> OcrTextScript.CYRILLIC
            char in 'A'..'Z' || char in 'a'..'z' ||
                char in '\u00C0'..'\u024F' ||
                char in '\u1E00'..'\u1EFF' -> OcrTextScript.LATIN
            char in '\u3040'..'\u30FF' ||
                char in '\u3400'..'\u4DBF' ||
                char in '\u4E00'..'\u9FFF' ||
                char in '\uAC00'..'\uD7AF' -> OcrTextScript.CJK
            char in '\u0900'..'\u097F' -> OcrTextScript.DEVANAGARI
            char in '\u0600'..'\u06FF' ||
                char in '\u0750'..'\u077F' ||
                char in '\u08A0'..'\u08FF' ||
                char in '\uFB50'..'\uFDFF' ||
                char in '\uFE70'..'\uFEFF' -> OcrTextScript.ARABIC
            char in '\u0590'..'\u05FF' -> OcrTextScript.HEBREW
            else -> OcrTextScript.OTHER
        }
    }

    private fun isConflictingScript(
        actualScript: OcrTextScript,
        expectedScript: OcrTextScript
    ): Boolean =
        actualScript != OcrTextScript.OTHER &&
            expectedScript != OcrTextScript.OTHER &&
            actualScript != expectedScript

    private suspend fun translateCaptureBlocks(
        blocks: List<PaintBlock>,
        sourceLanguage: Language,
        targetLanguage: Language
    ): List<String> {
        val texts = blocks.flatMap { block -> block.lines.map { it.text } }
        if (sourceLanguage.code == targetLanguage.code) return texts

        return if (translationEngine.isLoaded) {
            try {
                val translatedLines = mutableListOf<String>()
                for (block in blocks) {
                    translatedLines += translateCaptureBlockWithStructure(
                        block = block,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage
                    ) ?: translateCaptureLinesWithMainModel(
                        lines = block.lines,
                        sourceLanguage = sourceLanguage,
                        targetLanguage = targetLanguage
                    )
                }
                translatedLines
            } catch (e: Exception) {
                Log.e("CameraVM", "Structured HY-MT failed, falling back to ML Kit: ${e.message}")
                if (cameraTranslateEngine.isReady.value) cameraTranslateEngine.translateLines(texts) else texts
            }
        } else if (cameraTranslateEngine.isReady.value) {
            cameraTranslateEngine.translateLines(texts)
        } else {
            texts
        }
    }

    private suspend fun translateCaptureBlockWithStructure(
        block: PaintBlock,
        sourceLanguage: Language,
        targetLanguage: Language
    ): List<String>? {
        if (block.lines.size < 2) return null

        val ids = block.lines.mapIndexed { index, _ -> "L${index + 1}" }
        val sourceText = block.lines.mapIndexed { index, line ->
            "[${ids[index]}] ${line.text}"
        }.joinToString("\n")
        val maxTokens = (sourceText.length * 3).coerceIn(256, 2048)
        val translated = translationEngine.translateStructuredSafe(
            sourceText = sourceText,
            source = sourceLanguage,
            target = targetLanguage,
            maxTokens = maxTokens
        )

        val parsed = parseStructuredTranslations(translated, ids)
        if (parsed == null) {
            Log.w("CameraVM", "Structured translation did not preserve lines for ${block.id}")
            return null
        }

        return parsed.mapIndexed { index, text ->
            text.trim().ifBlank { block.lines[index].text }
        }
    }

    private suspend fun translateCaptureLinesWithMainModel(
        lines: List<PaintLine>,
        sourceLanguage: Language,
        targetLanguage: Language
    ): List<String> {
        val translatedLines = mutableListOf<String>()
        for (line in lines) {
            val translated = translationEngine.translateSafe(line.text, sourceLanguage, targetLanguage)
            translatedLines.add(translated.trim().ifBlank { line.text })
        }
        return translatedLines
    }

    private fun parseStructuredTranslations(
        translated: String,
        ids: List<String>
    ): List<String>? {
        val byMarker = parseStructuredTranslationsByMarker(translated, ids)
        if (byMarker != null) return byMarker

        val lines = translated.lines()
            .map { stripStructuredLineId(it).trim() }
            .filter { it.isNotBlank() }
        return if (lines.size == ids.size) lines else null
    }

    private fun parseStructuredTranslationsByMarker(
        translated: String,
        ids: List<String>
    ): List<String>? {
        val markerRegex = Regex("""\[(L\d+)]""")
        val matches = markerRegex.findAll(translated).toList()
        if (matches.size < ids.size) return null

        val valuesById = mutableMapOf<String, String>()
        for (index in matches.indices) {
            val id = matches[index].groupValues[1]
            if (id !in ids || id in valuesById) continue

            val valueStart = matches[index].range.last + 1
            val valueEnd = matches.getOrNull(index + 1)?.range?.first ?: translated.length
            valuesById[id] = translated.substring(valueStart, valueEnd).trim()
        }

        if (!ids.all { valuesById[it]?.isNotBlank() == true }) return null
        return ids.map { valuesById.getValue(it) }
    }

    private fun stripStructuredLineId(text: String): String =
        text.replace(Regex("""^\s*\[?L\d+]?\s*[:.)\-]?\s*"""), "")

    private fun rememberCaptureDebugSnapshot(
        requestedSourceLanguage: Language,
        effectiveSourceLanguage: Language,
        targetLanguage: Language,
        ocrResult: OcrResult,
        attempts: List<CaptureOcrAttempt>,
        lines: List<PaintLine>,
        translations: List<String>
    ) {
        lastCaptureDebugSnapshot = CaptureDebugSnapshot(
            createdAtMs = System.currentTimeMillis(),
            requestedSourceLanguage = requestedSourceLanguage,
            effectiveSourceLanguage = effectiveSourceLanguage,
            targetLanguage = targetLanguage,
            ocrBackend = ocrEngine.backendNameFor(effectiveSourceLanguage.code),
            ocrLanguage = ocrEngine.engineLanguageFor(effectiveSourceLanguage.code),
            imageWidth = ocrResult.imageWidth,
            imageHeight = ocrResult.imageHeight,
            blocks = ocrResult.blocks,
            attempts = attempts.map { attempt ->
                CaptureDebugAttempt(
                    languageCode = attempt.language.code,
                    backend = ocrEngine.backendNameFor(attempt.language.code),
                    engineLanguage = ocrEngine.engineLanguageFor(attempt.language.code),
                    expectedScript = attempt.score.expectedScript,
                    lineCount = attempt.score.lineCount,
                    textLength = attempt.score.textLength,
                    expectedScriptRatio = attempt.score.expectedScriptRatio,
                    selectionScore = attempt.score.selectionScore,
                    selected = attempt.language.code == effectiveSourceLanguage.code
                )
            },
            lines = lines.mapIndexed { index, line ->
                CaptureDebugLine(
                    id = line.id,
                    index = index,
                    sourceText = line.text,
                    translatedText = translations.getOrNull(index).orEmpty(),
                    box = Rect(line.box)
                )
            }
        )
    }

    fun saveDebugCapturePack() {
        val isDebuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) return

        val state = _uiState.value
        val snapshot = lastCaptureDebugSnapshot
        val original = state.capturedBitmap
        if (snapshot == null || original == null) {
            _uiState.update { it.copy(captureMessage = "Debug data is not ready") }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val packDir = writeCaptureDebugPack(
                    snapshot = snapshot,
                    original = original,
                    translatedOverlay = state.paintedBitmap ?: original
                )
                _uiState.update { it.copy(captureMessage = "Debug pack saved: ${packDir.name}") }
                Log.i("CameraVM", "Debug capture pack saved: ${packDir.absolutePath}")
            } catch (e: Exception) {
                Log.e("CameraVM", "Debug capture pack save failed: ${e.message}", e)
                _uiState.update { it.copy(captureMessage = "Debug pack save failed") }
            }
        }
    }

    private fun writeCaptureDebugPack(
        snapshot: CaptureDebugSnapshot,
        original: Bitmap,
        translatedOverlay: Bitmap
    ): File {
        val root = appContext.getExternalFilesDir("camera-debug")
            ?: File(appContext.filesDir, "camera-debug")
        val dirName = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
            .format(Date(snapshot.createdAtMs))
        val packDir = File(root, dirName)
        packDir.mkdirs()

        val files = JSONObject()
            .put("original", "original.png")
            .put("ocrBoxes", "ocr_boxes.png")
            .put("translatedOverlay", "translated_overlay.png")
            .put("recognizedText", "recognized.txt")
            .put("translationText", "translation.txt")

        saveBitmap(File(packDir, "original.png"), original)
        saveBitmap(File(packDir, "ocr_boxes.png"), drawDebugBoxes(original, snapshot.lines))
        saveBitmap(File(packDir, "translated_overlay.png"), translatedOverlay)
        File(packDir, "recognized.txt").writeText(
            snapshot.lines.joinToString("\n") { it.sourceText },
            Charsets.UTF_8
        )
        File(packDir, "translation.txt").writeText(
            snapshot.lines.joinToString("\n") { it.translatedText },
            Charsets.UTF_8
        )
        File(packDir, "metadata.json").writeText(
            snapshot.toJson(files).toString(2),
            Charsets.UTF_8
        )

        return packDir
    }

    private fun saveBitmap(file: File, bitmap: Bitmap) {
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        }
    }

    private fun drawDebugBoxes(original: Bitmap, lines: List<CaptureDebugLine>): Bitmap {
        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val strokePaint = Paint().apply {
            color = Color.argb(230, 255, 64, 64)
            style = Paint.Style.STROKE
            strokeWidth = max(2f, result.width / 700f)
            isAntiAlias = true
        }
        val fillPaint = Paint().apply {
            color = Color.argb(190, 0, 0, 0)
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val labelPaint = Paint().apply {
            color = Color.WHITE
            textSize = max(18f, result.width / 72f)
            isAntiAlias = true
            isFakeBoldText = true
        }

        for (line in lines) {
            val box = clippedBox(line.box, result.width, result.height)
            if (box.width() <= 0 || box.height() <= 0) continue
            canvas.drawRect(box, strokePaint)

            val label = (line.index + 1).toString()
            val labelWidth = labelPaint.measureText(label) + 10f
            val labelHeight = labelPaint.textSize + 8f
            val labelLeft = box.left.toFloat()
            val labelTop = (box.top - labelHeight).coerceAtLeast(0f)
            canvas.drawRect(
                labelLeft,
                labelTop,
                labelLeft + labelWidth,
                labelTop + labelHeight,
                fillPaint
            )
            canvas.drawText(label, labelLeft + 5f, labelTop + labelPaint.textSize + 1f, labelPaint)
        }

        return result
    }

    private fun CaptureDebugSnapshot.toJson(files: JSONObject): JSONObject =
        JSONObject()
            .put("createdAtMs", createdAtMs)
            .put(
                "languages",
                JSONObject()
                    .put("requestedSource", requestedSourceLanguage.code)
                    .put("effectiveSource", effectiveSourceLanguage.code)
                    .put("target", targetLanguage.code)
            )
            .put(
                "ocr",
                JSONObject()
                    .put("backend", ocrBackend)
                    .put("engineLanguage", ocrLanguage)
                    .put("imageWidth", imageWidth)
                    .put("imageHeight", imageHeight)
                    .put("blockCount", blocks.size)
                    .put("lineCount", lines.size)
            )
            .put("files", files)
            .put(
                "attempts",
                JSONArray().also { array ->
                    attempts.forEach { attempt ->
                        array.put(
                            JSONObject()
                                .put("language", attempt.languageCode)
                                .put("backend", attempt.backend)
                                .put("engineLanguage", attempt.engineLanguage)
                                .put("expectedScript", attempt.expectedScript.name)
                                .put("lineCount", attempt.lineCount)
                                .put("textLength", attempt.textLength)
                                .put("expectedScriptRatio", attempt.expectedScriptRatio.toDouble())
                                .put("selectionScore", attempt.selectionScore.toDouble())
                                .put("selected", attempt.selected)
                        )
                    }
                }
            )
            .put(
                "blocks",
                JSONArray().also { array ->
                    blocks.forEachIndexed { index, block ->
                        array.put(
                            JSONObject()
                                .put("index", index)
                                .put("text", block.text)
                                .put("box", block.boundingBox.toJson())
                                .put("lineCount", block.lines.size)
                        )
                    }
                }
            )
            .put(
                "lines",
                JSONArray().also { array ->
                    lines.forEach { line ->
                        array.put(
                            JSONObject()
                                .put("id", line.id)
                                .put("index", line.index)
                                .put("sourceText", line.sourceText)
                                .put("translatedText", line.translatedText)
                                .put("box", line.box.toJson())
                        )
                    }
                }
            )

    private fun Rect.toJson(): JSONObject =
        JSONObject()
            .put("left", left)
            .put("top", top)
            .put("right", right)
            .put("bottom", bottom)
            .put("width", width())
            .put("height", height())

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
        lastCaptureDebugSnapshot = null
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
