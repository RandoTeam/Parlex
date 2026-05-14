package com.translive.app.ui.viewmodel

import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.*
import android.media.ExifInterface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
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
import com.translive.app.engine.SystemTtsEngine
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
    val box: Rect,
    val sourceLanguage: Language? = null,
    val ocrLanguage: Language? = null,
    val rowIndex: Int? = null,
    val columnIndex: Int? = null
)

/** OCR block translated as one context unit, while keeping per-line boxes for painting. */
private data class PaintBlock(
    val id: String,
    val lines: List<PaintLine>
)

private data class DocumentRegion(
    val id: String,
    val lines: List<PaintLine>,
    val sourceText: String
)

private data class CaptureOcrPass(
    val result: OcrResult,
    val sourceLanguage: Language,
    val ocrLanguage: Language,
    val detectedSourceLanguages: List<Language>,
    val mixedBlocks: List<PaintBlock>,
    val attempts: List<CaptureOcrAttempt>
)

private data class CaptureOcrAttempt(
    val language: Language,
    val result: OcrResult,
    val score: CaptureOcrScore
)

private data class LiveOcrPass(
    val result: OcrResult,
    val sourceLanguage: Language,
    val ocrLanguage: Language
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
    val rowIndex: Int?,
    val columnIndex: Int?,
    val sourceLanguageCode: String,
    val ocrLanguageCode: String,
    val sourceText: String,
    val translatedText: String,
    val box: Rect
)

private data class MixedLineCandidate(
    val line: PaintLine,
    val score: Float
)

private data class CaptureTableRow(
    val lines: MutableList<PaintLine>,
    var centerY: Float,
    var averageHeight: Float
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
    val requestedSourceAuto: Boolean,
    val requestedSourceLanguage: Language,
    val effectiveSourceLanguage: Language,
    val selectedOcrLanguage: Language,
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

enum class CameraQualityWarning {
    LOW_LIGHT,
    SOFT_FOCUS,
    SMALL_TEXT,
    SCRIPT_MISMATCH,
    TRANSLATION_MODEL_UNAVAILABLE
}

data class CameraUiState(
    val mode: CameraMode = CameraMode.LIVE,
    val sourceLanguage: Language = Language.RUSSIAN,
    val isSourceAuto: Boolean = false,
    val detectedSourceLanguage: Language? = null,
    val detectedSourceLanguages: List<Language> = emptyList(),
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
    val nmtError: String? = null,
    val qualityWarnings: List<CameraQualityWarning> = emptyList()
) {
    val isCaptureProcessing: Boolean get() = captureStatus == CaptureStatus.PROCESSING
}

@HiltViewModel
class CameraViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val ocrEngine: OcrEngine,
    private val translationEngine: TranslationEngine,
    private val cameraTranslateEngine: CameraTranslateEngine,
    val systemTts: SystemTtsEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private var translateJob: Job? = null
    @Volatile
    private var lastCaptureDebugSnapshot: CaptureDebugSnapshot? = null

    @Volatile
    private var isLiveProcessing = false

    @Volatile
    private var isCaptureStarting = false

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
    private var lastAutoLiveSourceLanguage: Language? = null

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
        _uiState.update {
            it.copy(
                sourceLanguage = lang,
                isSourceAuto = false,
                detectedSourceLanguage = null,
                detectedSourceLanguages = emptyList()
            )
        }
        resetModeVisuals()
        prepareNmt()
    }

    fun setSourceAuto() {
        _uiState.update {
            it.copy(
                isSourceAuto = true,
                detectedSourceLanguage = null,
                detectedSourceLanguages = emptyList(),
                isNmtReady = false,
                nmtError = null
            )
        }
        resetModeVisuals()
        prepareNmt()
    }

    fun setTargetLanguage(lang: Language) {
        _uiState.update { it.copy(targetLanguage = lang) }
        resetModeVisuals()
        prepareNmt()
    }

    fun swapLanguages() {
        if (_uiState.value.isSourceAuto) return

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
                imageHeight = 0,
                detectedSourceLanguage = null,
                detectedSourceLanguages = emptyList(),
                qualityWarnings = emptyList()
            )
        }
    }

    private fun prepareNmt() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            if (state.isSourceAuto) {
                _uiState.update { it.copy(isNmtReady = false, nmtError = null) }
                return@launch
            }
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
                captureMessage = "Снимаю кадр",
                detectedSourceLanguage = null,
                detectedSourceLanguages = emptyList(),
                qualityWarnings = emptyList()
            )
        }
    }

    fun failFullResolutionCapture(message: String = "Не удалось снять кадр") {
        _uiState.update {
            it.copy(
                captureStatus = CaptureStatus.ERROR,
                captureMessage = message,
                detectedSourceLanguage = null,
                detectedSourceLanguages = emptyList(),
                qualityWarnings = emptyList()
            )
        }
    }

    fun captureGalleryImage(uri: Uri) {
        val state = _uiState.value
        translateJob?.cancel()
        clearLiveSession()
        lastCaptureDebugSnapshot = null
        _uiState.update {
            it.copy(
                mode = CameraMode.CAPTURE,
                capturedBitmap = null,
                paintedBitmap = null,
                liveBlocks = emptyList(),
                captureStatus = CaptureStatus.PROCESSING,
                captureMessage = "Открываю фото",
                detectedSourceLanguage = null,
                detectedSourceLanguages = emptyList(),
                qualityWarnings = emptyList()
            )
        }

        translateJob = viewModelScope.launch(Dispatchers.IO) {
            val bitmap = decodeGalleryBitmap(uri)
            if (bitmap == null) {
                _uiState.update {
                    it.copy(
                        captureStatus = CaptureStatus.ERROR,
                        captureMessage = "Не удалось открыть фото",
                        qualityWarnings = emptyList()
                    )
                }
                return@launch
            }

            val workBitmap = prepareCaptureBitmap(bitmap)
            enterCaptureMode(
                workBitmap = workBitmap,
                message = "Ищу текст на фото",
                cancelExistingJob = false
            )
            processCaptureBitmap(
                bitmap = workBitmap,
                sourceLanguage = state.sourceLanguage,
                sourceAuto = state.isSourceAuto,
                targetLanguage = state.targetLanguage
            )
        }
    }

    @androidx.camera.core.ExperimentalGetImage
    fun captureImage(imageProxy: androidx.camera.core.ImageProxy) {
        if (_uiState.value.mode != CameraMode.LIVE || isCaptureStarting) {
            imageProxy.close()
            return
        }

        isCaptureStarting = true
        clearLiveSession()
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
        if (_uiState.value.mode != CameraMode.LIVE || isCaptureStarting) {
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
                val liveOcr = recognizeLiveFrame(imageProxy, state)
                val ocrResult = liveOcr.result
                val rawLines = extractLiveLines(ocrResult)
                requestCameraTranslateModel(liveOcr.sourceLanguage, state.targetLanguage)
                val qualityWarnings = buildQualityWarnings(
                    ocrResult = ocrResult,
                    lineTexts = rawLines.map { it.text },
                    lineBoxes = rawLines.map { it.boundingBox },
                    sourceLanguage = liveOcr.sourceLanguage,
                    canTranslate = liveOcr.sourceLanguage.code == state.targetLanguage.code ||
                        cameraTranslateEngine.isReadyFor(liveOcr.sourceLanguage.code, state.targetLanguage.code)
                )

                if (rawLines.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            liveBlocks = emptyList(),
                            imageWidth = ocrResult.imageWidth,
                            imageHeight = ocrResult.imageHeight,
                            detectedSourceLanguage = if (state.isSourceAuto) {
                                it.detectedSourceLanguage
                            } else {
                                null
                            },
                            detectedSourceLanguages = if (state.isSourceAuto) {
                                it.detectedSourceLanguages
                            } else {
                                emptyList()
                            },
                            qualityWarnings = qualityWarnings
                        )
                    }
                    return@launch
                }

                val smoothedLines = smoothLiveLines(rawLines)
                val translatedBlocks = translateLiveLines(
                    lines = smoothedLines,
                    sourceLanguage = liveOcr.sourceLanguage,
                    targetLanguage = state.targetLanguage
                )

                if (_uiState.value.mode != CameraMode.LIVE || isCaptureStarting) return@launch

                _uiState.update {
                    it.copy(
                        liveBlocks = translatedBlocks,
                        imageWidth = ocrResult.imageWidth,
                        imageHeight = ocrResult.imageHeight,
                        detectedSourceLanguage = if (state.isSourceAuto) liveOcr.sourceLanguage else null,
                        detectedSourceLanguages = if (state.isSourceAuto) {
                            listOf(liveOcr.sourceLanguage)
                        } else {
                            emptyList()
                        },
                        qualityWarnings = qualityWarnings
                    )
                }
            } catch (e: Exception) {
                Log.e("CameraVM", "processLiveFrame error: ${e.message}", e)
            } finally {
                isLiveProcessing = false
            }
        }
    }

    private suspend fun recognizeLiveFrame(
        imageProxy: androidx.camera.core.ImageProxy,
        state: CameraUiState
    ): LiveOcrPass {
        if (!state.isSourceAuto) {
            return LiveOcrPass(
                result = ocrEngine.recognize(imageProxy, state.sourceLanguage.code),
                sourceLanguage = state.sourceLanguage,
                ocrLanguage = state.sourceLanguage
            )
        }

        val bitmap = try {
            ocrEngine.imageProxyToUprightBitmap(imageProxy)
        } finally {
            imageProxy.close()
        } ?: return LiveOcrPass(
            result = OcrResult(emptyList(), 0, 0),
            sourceLanguage = lastAutoLiveSourceLanguage ?: Language.ENGLISH,
            ocrLanguage = lastAutoLiveSourceLanguage ?: Language.ENGLISH
        )

        return recognizeAutoLiveBitmap(bitmap)
    }

    private suspend fun recognizeAutoLiveBitmap(bitmap: Bitmap): LiveOcrPass {
        val primaryLanguage = lastAutoLiveSourceLanguage ?: Language.ENGLISH
        val attempts = mutableListOf(recognizeCaptureAttempt(bitmap, primaryLanguage))
        val primaryScore = attempts.first().score
        val fallbackLanguage = when {
            primaryScore.isStrongForExpectedScript -> null
            expectedScriptForLanguage(primaryLanguage.code) == OcrTextScript.LATIN -> Language.RUSSIAN
            primaryLanguage == Language.RUSSIAN -> Language.ENGLISH
            else -> Language.ENGLISH
        }

        if (fallbackLanguage != null && fallbackLanguage != primaryLanguage) {
            attempts += recognizeCaptureAttempt(bitmap, fallbackLanguage)
        }

        val selectedAttempt = selectAutoOcrAttempt(attempts)
        val detectedLanguage = detectSourceLanguageFromText(
            selectedAttempt.result,
            selectedAttempt.language
        )
        lastAutoLiveSourceLanguage = detectedLanguage

        return LiveOcrPass(
            result = selectedAttempt.result,
            sourceLanguage = detectedLanguage,
            ocrLanguage = selectedAttempt.language
        )
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
        lastAutoLiveSourceLanguage = null
    }

    private fun requestCameraTranslateModel(sourceLanguage: Language, targetLanguage: Language) {
        if (sourceLanguage.code == targetLanguage.code) return
        if (cameraTranslateEngine.isReadyFor(sourceLanguage.code, targetLanguage.code)) return
        if (cameraTranslateEngine.isPreparingFor(sourceLanguage.code, targetLanguage.code)) return

        viewModelScope.launch(Dispatchers.IO) {
            val ok = cameraTranslateEngine.prepare(sourceLanguage.code, targetLanguage.code)
            _uiState.update {
                if (ok) {
                    it.copy(nmtError = null)
                } else {
                    it.copy(nmtError = "Модель перевода не скачана")
                }
            }
        }
    }

    private suspend fun prepareCaptureTranslateModel(sourceLanguage: Language, targetLanguage: Language) {
        if (sourceLanguage.code == targetLanguage.code) return
        if (cameraTranslateEngine.isReadyFor(sourceLanguage.code, targetLanguage.code)) return
        if (cameraTranslateEngine.isPreparingFor(sourceLanguage.code, targetLanguage.code)) return

        val ok = cameraTranslateEngine.prepare(sourceLanguage.code, targetLanguage.code)
        _uiState.update {
            if (ok) {
                it.copy(nmtError = null)
            } else {
                it.copy(nmtError = "Модель перевода не скачана")
            }
        }
    }

    private fun canCameraTranslateAll(
        lines: List<PaintLine>,
        defaultSourceLanguage: Language,
        targetLanguage: Language
    ): Boolean =
        lines
            .map { it.sourceLanguage ?: defaultSourceLanguage }
            .distinctBy { it.code }
            .all { sourceLanguage ->
                sourceLanguage.code == targetLanguage.code ||
                    cameraTranslateEngine.isReadyFor(sourceLanguage.code, targetLanguage.code)
            }

    private suspend fun translateLiveLines(
        lines: List<OcrLine>,
        sourceLanguage: Language,
        targetLanguage: Language
    ): List<TranslatedBlock> {
        if (sourceLanguage.code == targetLanguage.code) {
            return lines.map { line ->
                TranslatedBlock(line.text, line.text, line.boundingBox)
            }
        }

        if (!cameraTranslateEngine.isReadyFor(sourceLanguage.code, targetLanguage.code)) {
            return lines.map { line ->
                TranslatedBlock(line.text, "", line.boundingBox)
            }
        }

        val cacheKeys = lines.map {
            liveTranslationCacheKey(sourceLanguage.code, targetLanguage.code, it.text)
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
            isCaptureStarting = false
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
                sourceAuto = state.isSourceAuto,
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

    private fun enterCaptureMode(
        workBitmap: Bitmap,
        message: String = "Ищу текст на снимке",
        cancelExistingJob: Boolean = true
    ) {
        isCaptureStarting = false
        if (cancelExistingJob) translateJob?.cancel()
        clearLiveSession()
        lastCaptureDebugSnapshot = null
        _uiState.update {
            it.copy(
                mode = CameraMode.CAPTURE,
                capturedBitmap = workBitmap,
                paintedBitmap = workBitmap,
                liveBlocks = emptyList(),
                captureStatus = CaptureStatus.PROCESSING,
                captureMessage = message,
                detectedSourceLanguage = null,
                detectedSourceLanguages = emptyList(),
                qualityWarnings = emptyList()
            )
        }
    }

    private fun decodeGalleryBitmap(uri: Uri): Bitmap? {
        val resolver = appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = calculateBitmapSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxSize = GALLERY_DECODE_MAX_SIZE
            )
        }
        val bitmap = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            }
        }.getOrNull() ?: return null

        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        return applyExifOrientation(bitmap, orientation)
    }

    private fun calculateBitmapSampleSize(width: Int, height: Int, maxSize: Int): Int {
        if (width <= 0 || height <= 0) return 1

        var sampleSize = 1
        var longest = max(width, height)
        while (longest / sampleSize > maxSize) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            else -> return bitmap
        }

        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private suspend fun processCaptureBitmap(
        bitmap: Bitmap,
        sourceLanguage: Language,
        sourceAuto: Boolean,
        targetLanguage: Language
    ) {
        try {
            val captureOcr = recognizeCaptureBitmap(bitmap, sourceLanguage, sourceAuto)
            val ocrResult = captureOcr.result
            val effectiveSourceLanguage = captureOcr.sourceLanguage
            val rawCaptureLines = captureOcr.mixedBlocks
                .flatMap { it.lines }
                .ifEmpty { extractRawCaptureLines(ocrResult) }
            val qualityWarnings = buildQualityWarnings(
                ocrResult = ocrResult,
                lineTexts = rawCaptureLines.map { it.text },
                lineBoxes = rawCaptureLines.map { it.box },
                sourceLanguage = effectiveSourceLanguage,
                allowMixedScripts = sourceAuto && captureOcr.detectedSourceLanguages.size > 1,
                canTranslate = effectiveSourceLanguage.code == targetLanguage.code ||
                    translationEngine.isLoaded ||
                    canCameraTranslateAll(rawCaptureLines, effectiveSourceLanguage, targetLanguage)
            )

            if (rawCaptureLines.isEmpty()) {
                rememberCaptureDebugSnapshot(
                    sourceLanguage,
                    sourceAuto,
                    effectiveSourceLanguage,
                    captureOcr.ocrLanguage,
                    targetLanguage,
                    ocrResult,
                    captureOcr.attempts,
                    emptyList(),
                    emptyList()
                )
                _uiState.update {
                    it.copy(
                        captureStatus = CaptureStatus.EMPTY,
                        captureMessage = "Текст не найден",
                        detectedSourceLanguage = if (sourceAuto) effectiveSourceLanguage else null,
                        detectedSourceLanguages = if (sourceAuto) captureOcr.detectedSourceLanguages else emptyList(),
                        qualityWarnings = qualityWarnings
                    )
                }
                return
            }

            val captureBlocks = applyCaptureTableLayout(
                captureOcr.mixedBlocks.ifEmpty {
                    extractCaptureBlocks(ocrResult, effectiveSourceLanguage)
                }
            )
            val allLines = captureBlocks.flatMap { it.lines }

            if (allLines.isEmpty()) {
                rememberCaptureDebugSnapshot(
                    sourceLanguage,
                    sourceAuto,
                    effectiveSourceLanguage,
                    captureOcr.ocrLanguage,
                    targetLanguage,
                    ocrResult,
                    captureOcr.attempts,
                    emptyList(),
                    emptyList()
                )
                _uiState.update {
                    it.copy(
                        captureStatus = CaptureStatus.EMPTY,
                        captureMessage = "Подходящие строки не найдены",
                        detectedSourceLanguage = if (sourceAuto) effectiveSourceLanguage else null,
                        detectedSourceLanguages = if (sourceAuto) captureOcr.detectedSourceLanguages else emptyList(),
                        qualityWarnings = qualityWarnings
                    )
                }
                return
            }

            _uiState.update { it.copy(captureMessage = "Перевожу найденные строки") }

            if (!translationEngine.isLoaded) {
                prepareCaptureTranslateModel(effectiveSourceLanguage, targetLanguage)
            }
            val finalQualityWarnings = buildQualityWarnings(
                ocrResult = ocrResult,
                lineTexts = rawCaptureLines.map { it.text },
                lineBoxes = rawCaptureLines.map { it.box },
                sourceLanguage = effectiveSourceLanguage,
                allowMixedScripts = sourceAuto && captureOcr.detectedSourceLanguages.size > 1,
                canTranslate = effectiveSourceLanguage.code == targetLanguage.code ||
                    translationEngine.isLoaded ||
                    canCameraTranslateAll(allLines, effectiveSourceLanguage, targetLanguage)
            )
            val useDocumentLayout = shouldUseDocumentCaptureLayout(
                blocks = captureBlocks,
                sourceAuto = sourceAuto,
                detectedLanguages = captureOcr.detectedSourceLanguages
            )
            val translatedParts: List<String>
            val painted: Bitmap
            if (useDocumentLayout) {
                _uiState.update { it.copy(captureMessage = "Перевожу страницу по абзацам") }
                val regions = buildDocumentRegions(captureBlocks)
                val regionTranslations = translateCaptureDocumentRegions(
                    regions = regions,
                    sourceLanguage = effectiveSourceLanguage,
                    targetLanguage = targetLanguage
                )
                translatedParts = documentDebugTranslations(regions, regionTranslations, allLines)
                painted = paintDocumentRegionsOnBitmap(bitmap, regions, regionTranslations)
            } else {
                translatedParts = translateCaptureBlocks(captureBlocks, effectiveSourceLanguage, targetLanguage)
                painted = paintOnBitmap(bitmap, allLines, translatedParts)
            }
            rememberCaptureDebugSnapshot(
                sourceLanguage,
                sourceAuto,
                effectiveSourceLanguage,
                captureOcr.ocrLanguage,
                targetLanguage,
                ocrResult,
                captureOcr.attempts,
                allLines,
                translatedParts
            )

            _uiState.update {
                it.copy(
                    paintedBitmap = painted,
                    captureStatus = CaptureStatus.READY,
                    captureMessage = null,
                    detectedSourceLanguage = if (sourceAuto) effectiveSourceLanguage else null,
                    detectedSourceLanguages = if (sourceAuto) captureOcr.detectedSourceLanguages else emptyList(),
                    qualityWarnings = finalQualityWarnings
                )
            }
        } catch (e: Exception) {
            Log.e("CameraVM", "Capture error: ${e.message}", e)
            _uiState.update {
                it.copy(
                    captureStatus = CaptureStatus.ERROR,
                    captureMessage = "Ошибка обработки снимка",
                    detectedSourceLanguage = null,
                    detectedSourceLanguages = emptyList(),
                    qualityWarnings = emptyList()
                )
            }
        }
    }

    private suspend fun recognizeCaptureBitmap(
        bitmap: Bitmap,
        sourceLanguage: Language,
        sourceAuto: Boolean
    ): CaptureOcrPass {
        if (sourceAuto) {
            return recognizeAutoCaptureBitmap(bitmap)
        }

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
        return CaptureOcrPass(
            result = selectedAttempt.result,
            sourceLanguage = selectedAttempt.language,
            ocrLanguage = selectedAttempt.language,
            detectedSourceLanguages = listOf(selectedAttempt.language),
            mixedBlocks = emptyList(),
            attempts = attempts
        )
    }

    private suspend fun recognizeAutoCaptureBitmap(bitmap: Bitmap): CaptureOcrPass {
        val attempts = AUTO_CAPTURE_OCR_LANGUAGES.map { language ->
            recognizeCaptureAttempt(bitmap, language)
        }
        val selectedAttempt = selectAutoOcrAttempt(attempts)
        val mixedBlocks = buildMixedCaptureBlocks(attempts)
        val mixedLines = mixedBlocks.flatMap { it.lines }
        val detectedLanguages = mixedLines
            .map { it.sourceLanguage ?: selectedAttempt.language }
            .distinctBy { it.code }
            .ifEmpty {
                listOf(detectSourceLanguageFromText(selectedAttempt.result, selectedAttempt.language))
            }
        val detectedLanguage = dominantSourceLanguage(
            lines = mixedLines,
            fallback = detectedLanguages.first()
        )
        Log.i(
            "CameraVM",
            "Auto capture OCR selected ${selectedAttempt.language.code}, source=${detectedLanguage.code}, " +
                "languages=${detectedLanguages.joinToString { it.code }}, lines=${mixedLines.size}"
        )

        return CaptureOcrPass(
            result = selectedAttempt.result,
            sourceLanguage = detectedLanguage,
            ocrLanguage = selectedAttempt.language,
            detectedSourceLanguages = detectedLanguages,
            mixedBlocks = mixedBlocks,
            attempts = attempts
        )
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

    private fun buildMixedCaptureBlocks(attempts: List<CaptureOcrAttempt>): List<PaintBlock> {
        val selectedCandidates = mutableListOf<MixedLineCandidate>()

        attempts.forEachIndexed { attemptIndex, attempt ->
            val rawLines = extractRawCaptureLines(attempt.result)
            rawLines.forEach { line ->
                val sourceLanguage = detectSourceLanguageForText(line.text, attempt.language)
                val candidate = MixedLineCandidate(
                    line = line.copy(
                        id = "a$attemptIndex:${line.id}",
                        sourceLanguage = sourceLanguage,
                        ocrLanguage = attempt.language
                    ),
                    score = scoreMixedLineCandidate(
                        line = line,
                        sourceLanguage = sourceLanguage,
                        ocrLanguage = attempt.language,
                        attemptScore = attempt.score
                    )
                )

                if (candidate.score < MIXED_LINE_MIN_SCORE) return@forEach
                val overlappingIndex = selectedCandidates.indexOfFirst {
                    lineBoxesOverlap(it.line.box, candidate.line.box)
                }
                if (overlappingIndex < 0) {
                    selectedCandidates += candidate
                } else if (candidate.score > selectedCandidates[overlappingIndex].score) {
                    selectedCandidates[overlappingIndex] = candidate
                }
            }
        }

        val lines = selectedCandidates
            .sortedWith(compareBy<MixedLineCandidate>({ it.line.box.top }, { it.line.box.left }))
            .mapIndexed { index, candidate ->
                candidate.line.copy(id = "m$index")
            }

        return if (lines.isEmpty()) emptyList() else listOf(PaintBlock("mixed", lines))
    }

    private fun detectSourceLanguageForText(text: String, ocrLanguage: Language): Language =
        when (expectedScriptForLanguage(ocrLanguage.code)) {
            OcrTextScript.LATIN -> guessLatinLanguage(text)
            OcrTextScript.CJK -> guessCjkLanguage(text)
            OcrTextScript.CYRILLIC -> guessCyrillicLanguage(text)
            else -> ocrLanguage
        }

    private fun scoreMixedLineCandidate(
        line: PaintLine,
        sourceLanguage: Language,
        ocrLanguage: Language,
        attemptScore: CaptureOcrScore
    ): Float {
        val sourceScript = expectedScriptForLanguage(sourceLanguage.code)
        val ocrScript = expectedScriptForLanguage(ocrLanguage.code)
        var letterCount = 0
        var sourceLetters = 0
        var conflictLetters = 0

        for (char in line.text) {
            val script = scriptForChar(char)
            if (script == OcrTextScript.OTHER) continue

            letterCount++
            when {
                script == sourceScript -> sourceLetters++
                isConflictingScript(script, sourceScript) -> conflictLetters++
            }
        }

        val scriptRatio = if (letterCount > 0 && sourceScript != OcrTextScript.OTHER) {
            sourceLetters.toFloat() / letterCount.toFloat()
        } else {
            1f
        }
        val geometryScore = line.box.width().coerceAtMost(800) * 0.015f +
            line.box.height().coerceAtMost(120) * 0.12f
        val textScore = line.text.count { !it.isWhitespace() }.coerceAtMost(80) * 0.6f
        val backendBonus = if (sourceScript == ocrScript) 12f else 0f
        val attemptBonus = if (attemptScore.isStrongForExpectedScript) 6f else 0f

        return geometryScore +
            textScore +
            sourceLetters * 2.4f -
            conflictLetters * 3.2f +
            scriptRatio * 22f +
            backendBonus +
            attemptBonus
    }

    private fun lineBoxesOverlap(first: Rect, second: Rect): Boolean {
        val left = max(first.left, second.left)
        val top = max(first.top, second.top)
        val right = minOf(first.right, second.right)
        val bottom = minOf(first.bottom, second.bottom)
        if (right <= left || bottom <= top) return false

        val intersection = (right - left).toFloat() * (bottom - top).toFloat()
        val smallerArea = minOf(first.width() * first.height(), second.width() * second.height())
            .coerceAtLeast(1)
            .toFloat()
        return intersection / smallerArea > MIXED_LINE_OVERLAP_THRESHOLD
    }

    private fun dominantSourceLanguage(lines: List<PaintLine>, fallback: Language): Language =
        lines
            .groupBy { it.sourceLanguage ?: fallback }
            .maxByOrNull { (_, groupedLines) ->
                groupedLines.sumOf { it.text.count { char -> !char.isWhitespace() } }
            }
            ?.key
            ?: fallback

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

    private fun selectAutoOcrAttempt(attempts: List<CaptureOcrAttempt>): CaptureOcrAttempt =
        attempts.maxByOrNull { attempt ->
            var score = attempt.score.selectionScore
            if (attempt.score.isStrongForExpectedScript) score += 18f else score -= 10f
            if (attempt.language == Language.ENGLISH) score += 6f
            score
        } ?: attempts.first()

    private fun detectSourceLanguageFromText(
        result: OcrResult,
        ocrLanguage: Language
    ): Language {
        val text = extractRawCaptureLines(result).joinToString(" ") { it.text }
        return when (expectedScriptForLanguage(ocrLanguage.code)) {
            OcrTextScript.LATIN -> guessLatinLanguage(text)
            OcrTextScript.CJK -> guessCjkLanguage(text)
            OcrTextScript.CYRILLIC -> guessCyrillicLanguage(text)
            else -> ocrLanguage
        }
    }

    private fun guessLatinLanguage(text: String): Language {
        if (text.isBlank()) return Language.ENGLISH

        val lowerText = text.lowercase(Locale.ROOT)
        val scores = linkedMapOf(
            Language.ENGLISH to languageHeuristicScore(lowerText, ENGLISH_HINTS, ENGLISH_CHARS),
            Language.FRENCH to languageHeuristicScore(lowerText, FRENCH_HINTS, FRENCH_CHARS),
            Language.GERMAN to languageHeuristicScore(lowerText, GERMAN_HINTS, GERMAN_CHARS),
            Language.CZECH to languageHeuristicScore(lowerText, CZECH_HINTS, CZECH_CHARS),
            Language.SPANISH to languageHeuristicScore(lowerText, SPANISH_HINTS, SPANISH_CHARS),
            Language.PORTUGUESE to languageHeuristicScore(lowerText, PORTUGUESE_HINTS, PORTUGUESE_CHARS),
            Language.POLISH to languageHeuristicScore(lowerText, POLISH_HINTS, POLISH_CHARS),
            Language.TURKISH to languageHeuristicScore(lowerText, TURKISH_HINTS, TURKISH_CHARS),
            Language.VIETNAMESE to languageHeuristicScore(lowerText, VIETNAMESE_HINTS, VIETNAMESE_CHARS)
        )

        return scores.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: Language.ENGLISH
    }

    private fun languageHeuristicScore(
        lowerText: String,
        hints: Set<String>,
        distinctiveChars: Set<Char>
    ): Int {
        val words = lowerText.split(Regex("""[^a-z\u00c0-\u024f\u1e00-\u1eff]+"""))
            .filter { it.isNotBlank() }
        val hintScore = words.count { it in hints } * 3
        val charScore = lowerText.count { it in distinctiveChars } * 4
        return hintScore + charScore
    }

    private fun guessCjkLanguage(text: String): Language =
        when {
            text.any { it in '\uAC00'..'\uD7AF' } -> Language.KOREAN
            text.any { it in '\u3040'..'\u30FF' } -> Language.JAPANESE
            else -> Language.CHINESE_SIMPLIFIED
        }

    private fun guessCyrillicLanguage(text: String): Language =
        if (text.any { it in UKRAINIAN_CHARS }) Language.UKRAINIAN else Language.RUSSIAN

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

    private fun applyCaptureTableLayout(blocks: List<PaintBlock>): List<PaintBlock> {
        val allLines = blocks.flatMap { it.lines }
        if (allLines.size < 2) return blocks

        val rows = buildCaptureRows(allLines)
        if (!looksLikeCaptureTable(rows)) {
            return sortCaptureBlocksNaturally(blocks)
        }

        val assignedById = rows
            .sortedBy { it.centerY }
            .flatMapIndexed { rowIndex, row ->
                row.lines
                    .sortedBy { it.box.left }
                    .mapIndexed { columnIndex, line ->
                        line.copy(rowIndex = rowIndex, columnIndex = columnIndex)
                    }
            }
            .associateBy { it.id }

        return sortCaptureBlocksNaturally(
            blocks.map { block ->
                block.copy(lines = block.lines.map { line -> assignedById[line.id] ?: line })
            }
        )
    }

    private fun buildCaptureRows(lines: List<PaintLine>): List<CaptureTableRow> {
        val rows = mutableListOf<CaptureTableRow>()
        lines
            .sortedWith(compareBy<PaintLine>({ centerY(it.box) }, { it.box.left }))
            .forEach { line ->
                val lineCenterY = centerY(line.box)
                val row = rows.firstOrNull { existingRow ->
                    val threshold = max(existingRow.averageHeight, line.box.height().toFloat()) *
                        TABLE_ROW_MERGE_HEIGHT_FACTOR
                    abs(existingRow.centerY - lineCenterY) <= threshold
                }

                if (row == null) {
                    rows += CaptureTableRow(
                        lines = mutableListOf(line),
                        centerY = lineCenterY,
                        averageHeight = line.box.height().toFloat()
                    )
                } else {
                    row.lines += line
                    row.centerY = row.lines.map { centerY(it.box) }.average().toFloat()
                    row.averageHeight = row.lines.map { it.box.height() }.average().toFloat()
                }
            }

        return rows
    }

    private fun looksLikeCaptureTable(rows: List<CaptureTableRow>): Boolean {
        val usableRows = rows.filter { it.lines.isNotEmpty() }
        if (usableRows.size < TABLE_MIN_ROWS) return false

        val multiColumnRows = usableRows.filter { rowHasSeparatedColumns(it) }
        val requiredMultiRows = max(
            TABLE_MIN_MULTI_COLUMN_ROWS,
            (usableRows.size * TABLE_MIN_MULTI_COLUMN_ROW_RATIO).toInt()
        )
        return multiColumnRows.size >= requiredMultiRows
    }

    private fun rowHasSeparatedColumns(row: CaptureTableRow): Boolean {
        val ordered = row.lines.sortedBy { it.box.left }
        if (ordered.size < 2) return false

        val minGap = row.averageHeight * TABLE_COLUMN_GAP_HEIGHT_FACTOR
        return ordered.zipWithNext().any { (left, right) ->
            right.box.left - left.box.right >= minGap
        }
    }

    private fun sortCaptureBlocksNaturally(blocks: List<PaintBlock>): List<PaintBlock> =
        blocks.map { block ->
            block.copy(
                lines = block.lines
                    .sortedWith(
                        compareBy<PaintLine>(
                            { it.rowIndex ?: Int.MAX_VALUE },
                            { it.columnIndex ?: Int.MAX_VALUE },
                            { it.box.top },
                            { it.box.left }
                        )
                    )
            )
        }.sortedWith(
            compareBy<PaintBlock>(
                { block -> block.lines.minOfOrNull { it.rowIndex ?: Int.MAX_VALUE } ?: Int.MAX_VALUE },
                { block -> block.lines.minOfOrNull { it.columnIndex ?: Int.MAX_VALUE } ?: Int.MAX_VALUE },
                { block -> block.lines.minOfOrNull { it.box.top } ?: Int.MAX_VALUE },
                { block -> block.lines.minOfOrNull { it.box.left } ?: Int.MAX_VALUE }
            )
        )

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

    private fun buildQualityWarnings(
        ocrResult: OcrResult,
        lineTexts: List<String>,
        lineBoxes: List<Rect>,
        sourceLanguage: Language,
        allowMixedScripts: Boolean = false,
        canTranslate: Boolean
    ): List<CameraQualityWarning> {
        val warnings = mutableListOf<CameraQualityWarning>()
        val quality = ocrResult.quality

        if (quality != null && quality.averageLuma < LOW_LIGHT_LUMA_THRESHOLD) {
            warnings += CameraQualityWarning.LOW_LIGHT
        }

        if (isSmallText(ocrResult.imageHeight, lineBoxes)) {
            warnings += CameraQualityWarning.SMALL_TEXT
        }

        if (quality != null && quality.sharpness < SOFT_FOCUS_THRESHOLD) {
            warnings += CameraQualityWarning.SOFT_FOCUS
        }

        if (!allowMixedScripts && hasScriptMismatch(lineTexts, sourceLanguage.code)) {
            warnings += CameraQualityWarning.SCRIPT_MISMATCH
        }

        if (!canTranslate) {
            warnings += CameraQualityWarning.TRANSLATION_MODEL_UNAVAILABLE
        }

        return warnings.distinct()
    }

    private fun isSmallText(imageHeight: Int, lineBoxes: List<Rect>): Boolean {
        if (imageHeight <= 0 || lineBoxes.isEmpty()) return false
        val normalizedHeights = lineBoxes
            .map { it.height().toFloat() / imageHeight.toFloat() }
            .sorted()
        val medianHeight = normalizedHeights[normalizedHeights.size / 2]
        return medianHeight < SMALL_TEXT_HEIGHT_RATIO_THRESHOLD
    }

    private fun hasScriptMismatch(texts: List<String>, languageCode: String): Boolean {
        val expectedScript = expectedScriptForLanguage(languageCode)
        if (expectedScript == OcrTextScript.OTHER || texts.isEmpty()) return false

        var letterCount = 0
        var expectedLetterCount = 0
        var conflictingLetterCount = 0

        for (text in texts) {
            for (char in text) {
                val script = scriptForChar(char)
                if (script == OcrTextScript.OTHER) continue

                letterCount++
                when {
                    script == expectedScript -> expectedLetterCount++
                    isConflictingScript(script, expectedScript) -> conflictingLetterCount++
                }
            }
        }

        if (letterCount < SCRIPT_MISMATCH_MIN_LETTERS) return false
        val conflictRatio = conflictingLetterCount.toFloat() / letterCount.toFloat()
        return expectedLetterCount == 0 && conflictingLetterCount >= SCRIPT_MISMATCH_MIN_CONFLICTS ||
            conflictRatio >= SCRIPT_MISMATCH_RATIO_THRESHOLD &&
            conflictingLetterCount > expectedLetterCount * 2
    }

    private fun shouldUseDocumentCaptureLayout(
        blocks: List<PaintBlock>,
        sourceAuto: Boolean,
        detectedLanguages: List<Language>
    ): Boolean {
        if (sourceAuto && detectedLanguages.size > 1) return false

        val lines = blocks.flatMap { it.lines }
        if (lines.size < DOCUMENT_LAYOUT_MIN_LINES) return false
        if (lines.any { it.rowIndex != null || it.columnIndex != null }) return false

        val textLength = lines.sumOf { it.text.count { char -> !char.isWhitespace() } }
        if (textLength < DOCUMENT_LAYOUT_MIN_CHARS) return false

        val pageBox = unionPaintLineBox(lines) ?: return false
        val medianHeight = lines.map { it.box.height() }.sorted().let { heights ->
            heights[heights.size / 2].toFloat()
        }
        val tallTextArea = pageBox.height() > medianHeight * DOCUMENT_LAYOUT_MIN_HEIGHT_LINES
        val wideTextArea = lines.count { it.box.width() > pageBox.width() * DOCUMENT_LAYOUT_WIDE_LINE_RATIO } >=
            lines.size / 2

        return tallTextArea && wideTextArea
    }

    private fun buildDocumentRegions(blocks: List<PaintBlock>): List<DocumentRegion> {
        val regions = mutableListOf<DocumentRegion>()
        for (block in blocks) {
            regions += splitDocumentBlock(block)
        }
        return regions.filter { it.sourceText.isNotBlank() }
    }

    private fun splitDocumentBlock(block: PaintBlock): List<DocumentRegion> {
        val sortedLines = block.lines
            .filterNot { isDocumentPageMarker(it.text) }
            .sortedWith(compareBy<PaintLine>({ it.box.top }, { it.box.left }))
        if (sortedLines.isEmpty()) return emptyList()

        val medianHeight = sortedLines.map { it.box.height() }.sorted().let { heights ->
            heights[heights.size / 2].toFloat()
        }
        val regions = mutableListOf<List<PaintLine>>()
        var current = mutableListOf<PaintLine>()
        var previous: PaintLine? = null

        for (line in sortedLines) {
            val prev = previous
            val startsNewRegion = prev != null && current.isNotEmpty() && (
                line.box.top - prev.box.bottom > medianHeight * DOCUMENT_REGION_GAP_HEIGHT_FACTOR ||
                    current.size >= DOCUMENT_REGION_MAX_LINES
                )

            if (startsNewRegion) {
                regions += current.toList()
                current = mutableListOf()
            }

            current += line
            previous = line
        }

        if (current.isNotEmpty()) regions += current.toList()

        return regions.mapIndexed { index, lines ->
            DocumentRegion(
                id = "${block.id}:r$index",
                lines = lines,
                sourceText = buildCaptureDocumentText(lines)
            )
        }
    }

    private fun documentDebugTranslations(
        regions: List<DocumentRegion>,
        translations: List<String>,
        allLines: List<PaintLine>
    ): List<String> {
        val byLineId = mutableMapOf<String, String>()
        regions.forEachIndexed { regionIndex, region ->
            region.lines.forEachIndexed { lineIndex, line ->
                byLineId[line.id] = if (lineIndex == 0) {
                    translations.getOrNull(regionIndex).orEmpty()
                } else {
                    ""
                }
            }
        }
        return allLines.map { line -> byLineId[line.id].orEmpty() }
    }

    private suspend fun translateCaptureDocumentRegions(
        regions: List<DocumentRegion>,
        sourceLanguage: Language,
        targetLanguage: Language
    ): List<String> {
        if (regions.isEmpty()) return emptyList()
        return regions.map { region ->
            translateCaptureDocumentRegion(region.sourceText, sourceLanguage, targetLanguage)
        }
    }

    private suspend fun translateCaptureDocumentRegion(
        sourceText: String,
        sourceLanguage: Language,
        targetLanguage: Language
    ): String {
        if (sourceText.isBlank()) return ""
        if (sourceLanguage.code == targetLanguage.code) return sourceText

        if (translationEngine.isLoaded) {
            runCatching {
                val maxTokens = (sourceText.length * 2).coerceIn(512, 2048)
                translationEngine.translateSafe(sourceText, sourceLanguage, targetLanguage, maxTokens)
            }.onSuccess { translated ->
                val normalized = sanitizeDocumentTranslation(translated)
                if (normalized.isNotBlank()) return normalized
            }.onFailure { error ->
                Log.e("CameraVM", "Document HY-MT failed for ${sourceLanguage.code}: ${error.message}")
            }
        }

        prepareCaptureTranslateModel(sourceLanguage, targetLanguage)
        return if (cameraTranslateEngine.isReadyFor(sourceLanguage.code, targetLanguage.code)) {
            cameraTranslateEngine.translateLines(listOf(sourceText))
                .firstOrNull()
                ?.let { sanitizeDocumentTranslation(it) }
                ?.takeIf { it.isNotBlank() }
                ?: sourceText
        } else {
            sourceText
        }
    }

    private fun sanitizeDocumentTranslation(text: String): String =
        text.lines()
            .map { line -> stripStructuredLineId(line).trim() }
            .filterNot { line ->
                line.contains("Return one translated", ignoreCase = true) ||
                    line.contains("Do not add explanations", ignoreCase = true) ||
                    line.contains("Preserve every line ID", ignoreCase = true) ||
                    line.contains("Translate the OCR", ignoreCase = true)
            }
            .joinToString("\n")
            .trim()

    private fun buildCaptureDocumentText(lines: List<PaintLine>): String {
        val sortedLines = lines.sortedWith(compareBy<PaintLine>({ it.box.top }, { it.box.left }))
        val builder = StringBuilder()
        for (line in sortedLines) {
            val text = line.text.replace(Regex("\\s+"), " ").trim()
            if (text.isBlank()) continue

            if (builder.isEmpty()) {
                builder.append(text)
            } else if (builder.endsWithHyphen()) {
                builder.setLength(builder.length - 1)
                builder.append(text)
            } else {
                builder.append(' ').append(text)
            }
        }
        return builder.toString()
    }

    private fun isDocumentPageMarker(text: String): Boolean {
        val normalized = text
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replace(Regex("\\s+"), "")
        return normalized.matches(Regex("""-?\d{1,4}-?"""))
    }

    private suspend fun translateCaptureBlocks(
        blocks: List<PaintBlock>,
        sourceLanguage: Language,
        targetLanguage: Language
    ): List<String> {
        val translatedByLineId = mutableMapOf<String, String>()

        for (block in blocks) {
            val groupedLines = block.lines.groupBy { it.sourceLanguage ?: sourceLanguage }
            for ((lineSourceLanguage, sourceLines) in groupedLines) {
                val sourceBlock = block.copy(lines = sourceLines)
                val translations = translateCaptureSingleSourceBlock(
                    block = sourceBlock,
                    sourceLanguage = lineSourceLanguage,
                    targetLanguage = targetLanguage
                )
                sourceLines.forEachIndexed { index, line ->
                    translatedByLineId[line.id] = translations.getOrElse(index) { line.text }
                }
            }
        }

        return blocks.flatMap { block ->
            block.lines.map { line -> translatedByLineId[line.id] ?: line.text }
        }
    }

    private suspend fun translateCaptureSingleSourceBlock(
        block: PaintBlock,
        sourceLanguage: Language,
        targetLanguage: Language
    ): List<String> {
        val texts = block.lines.map { it.text }
        if (sourceLanguage.code == targetLanguage.code) return texts

        return if (translationEngine.isLoaded) {
            try {
                translateCaptureBlockWithStructure(
                    block = block,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage
                ) ?: translateCaptureLinesWithMainModel(
                    lines = block.lines,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage
                )
            } catch (e: Exception) {
                Log.e("CameraVM", "Structured HY-MT failed for ${sourceLanguage.code}: ${e.message}")
                translateCaptureLinesWithCameraModel(block.lines, sourceLanguage, targetLanguage)
            }
        } else {
            translateCaptureLinesWithCameraModel(block.lines, sourceLanguage, targetLanguage)
        }
    }

    private suspend fun translateCaptureLinesWithCameraModel(
        lines: List<PaintLine>,
        sourceLanguage: Language,
        targetLanguage: Language
    ): List<String> {
        val texts = lines.map { it.text }
        if (sourceLanguage.code == targetLanguage.code) return texts

        prepareCaptureTranslateModel(sourceLanguage, targetLanguage)
        return if (cameraTranslateEngine.isReadyFor(sourceLanguage.code, targetLanguage.code)) {
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
        requestedSourceAuto: Boolean,
        effectiveSourceLanguage: Language,
        selectedOcrLanguage: Language,
        targetLanguage: Language,
        ocrResult: OcrResult,
        attempts: List<CaptureOcrAttempt>,
        lines: List<PaintLine>,
        translations: List<String>
    ) {
        lastCaptureDebugSnapshot = CaptureDebugSnapshot(
            createdAtMs = System.currentTimeMillis(),
            requestedSourceAuto = requestedSourceAuto,
            requestedSourceLanguage = requestedSourceLanguage,
            effectiveSourceLanguage = effectiveSourceLanguage,
            selectedOcrLanguage = selectedOcrLanguage,
            targetLanguage = targetLanguage,
            ocrBackend = ocrEngine.backendNameFor(selectedOcrLanguage.code),
            ocrLanguage = ocrEngine.engineLanguageFor(selectedOcrLanguage.code),
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
                    selected = attempt.language.code == selectedOcrLanguage.code
                )
            },
            lines = lines.mapIndexed { index, line ->
                CaptureDebugLine(
                    id = line.id,
                    index = index,
                    rowIndex = line.rowIndex,
                    columnIndex = line.columnIndex,
                    sourceLanguageCode = (line.sourceLanguage ?: effectiveSourceLanguage).code,
                    ocrLanguageCode = (line.ocrLanguage ?: selectedOcrLanguage).code,
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
            snapshot.lines.joinToString("\n") { line ->
                "[${line.sourceLanguageCode}${line.tableCellLabel()}] ${line.sourceText}"
            },
            Charsets.UTF_8
        )
        File(packDir, "translation.txt").writeText(
            snapshot.lines.joinToString("\n") { line ->
                "[${line.sourceLanguageCode}${line.tableCellLabel()}] ${line.translatedText}"
            },
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

            val label = if (line.rowIndex != null && line.columnIndex != null) {
                "${line.rowIndex + 1}.${line.columnIndex + 1}"
            } else {
                (line.index + 1).toString()
            }
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
                    .put("requestedSource", if (requestedSourceAuto) "auto" else requestedSourceLanguage.code)
                    .put("effectiveSource", effectiveSourceLanguage.code)
                    .put("selectedOcrSource", selectedOcrLanguage.code)
                    .put(
                        "lineSources",
                        JSONArray().also { array ->
                            lines.map { it.sourceLanguageCode }.distinct().forEach { code ->
                                array.put(code)
                            }
                        }
                    )
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
            .put(
                "table",
                JSONObject()
                    .put("rowCount", lines.mapNotNull { it.rowIndex }.distinct().size)
                    .put("maxColumnCount", maxColumnCountByRow())
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
                                .put("rowIndex", line.rowIndex?.plus(1) ?: JSONObject.NULL)
                                .put("columnIndex", line.columnIndex?.plus(1) ?: JSONObject.NULL)
                                .put("sourceLanguage", line.sourceLanguageCode)
                                .put("ocrLanguage", line.ocrLanguageCode)
                                .put("sourceText", line.sourceText)
                                .put("translatedText", line.translatedText)
                                .put("box", line.box.toJson())
                        )
                    }
                }
            )

    private fun CaptureDebugSnapshot.maxColumnCountByRow(): Int =
        lines
            .filter { it.rowIndex != null && it.columnIndex != null }
            .groupBy { it.rowIndex }
            .values
            .maxOfOrNull { rowLines ->
                rowLines.maxOf { line -> line.columnIndex ?: -1 } + 1
            } ?: 0

    private fun Rect.toJson(): JSONObject =
        JSONObject()
            .put("left", left)
            .put("top", top)
            .put("right", right)
            .put("bottom", bottom)
            .put("width", width())
            .put("height", height())

    private fun CaptureDebugLine.tableCellLabel(): String =
        if (rowIndex != null && columnIndex != null) {
            " r${rowIndex + 1}c${columnIndex + 1}"
        } else {
            ""
        }

    private fun paintDocumentRegionsOnBitmap(
        original: Bitmap,
        regions: List<DocumentRegion>,
        translations: List<String>
    ): Bitmap {
        if (regions.isEmpty()) return original

        val result = original.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(result)
        val allLines = regions.flatMap { it.lines }
        val pageBox = unionPaintLineBox(allLines)
            ?.let { expandedDocumentPageBox(it, result.width, result.height) }
            ?: return result

        val pageColor = sampleDocumentPaperColor(original, pageBox)
        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            color = Color.argb(
                255,
                Color.red(pageColor),
                Color.green(pageColor),
                Color.blue(pageColor)
            )
        }
        canvas.drawRect(pageBox, bgPaint)

        val textPaint = TextPaint().apply {
            isAntiAlias = true
            isFakeBoldText = false
            color = contrastColor(pageColor)
        }

        regions.forEachIndexed { index, region ->
            val text = translations.getOrNull(index)
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
            if (text.isBlank()) return@forEachIndexed

            val regionBox = unionPaintLineBox(region.lines)
                ?.let { expandedDocumentRegionBox(it, result.width, result.height) }
                ?: return@forEachIndexed
            if (regionBox.width() <= 0 || regionBox.height() <= 0) return@forEachIndexed

            val regionLineHeight = medianPaintLineHeight(region.lines, result.width, result.height)
            val paddingX = (regionLineHeight * 0.18f).coerceIn(3f, 9f)
            val paddingY = (regionLineHeight * 0.12f).coerceIn(2f, 7f)
            val maxTextWidth = (regionBox.width() - paddingX * 2f).toInt().coerceAtLeast(1)
            val maxTextHeight = (regionBox.height() - paddingY * 2f).coerceAtLeast(1f)

            textPaint.textSize = (regionLineHeight * 0.66f).coerceIn(11f, 30f)
            val layout = buildDocumentPageTextLayout(
                text = text,
                paint = textPaint,
                width = maxTextWidth,
                maxHeight = maxTextHeight,
                minTextSize = DOCUMENT_REGION_MIN_TEXT_SIZE,
                maxTextSize = (regionLineHeight * 0.9f).coerceIn(13f, 34f)
            )

            val textLeft = regionBox.left.toFloat() + paddingX
            val textTop = regionBox.top.toFloat() + paddingY
            canvas.save()
            canvas.clipRect(
                textLeft,
                textTop,
                textLeft + maxTextWidth,
                regionBox.bottom.toFloat() - paddingY
            )
            canvas.translate(textLeft, textTop)
            layout.draw(canvas)
            canvas.restore()
        }

        return result
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
            isFakeBoldText = false
        }

        val medianBoxHeight = medianPaintLineHeight(lines, result.width, result.height)
        val stableMinTextSize = (medianBoxHeight * 0.44f).coerceIn(10f, 22f)
        val stableMaxTextSize = (medianBoxHeight * 0.76f).coerceIn(14f, 34f)

        for (i in lines.indices) {
            val line = lines[i]
            val translatedText = translations.getOrNull(i)
                ?.takeIf { it.isNotBlank() }
                ?: line.text

            val box = clippedBox(line.box, result.width, result.height)
            if (box.width() <= 0 || box.height() <= 0) continue

            val paintBox = expandedBox(box, result.width, result.height)
            // Sample background color around the box to choose contrast
            val bgColor = sampleBackgroundColor(original, paintBox)
            val textColor = contrastColor(bgColor)

            // Draw semi-transparent background (matching surrounding color)
            bgPaint.color = Color.argb(232,
                Color.red(bgColor),
                Color.green(bgColor),
                Color.blue(bgColor)
            )
            val radius = (paintBox.height() * 0.12f).coerceIn(3f, 10f)
            canvas.drawRoundRect(RectF(paintBox), radius, radius, bgPaint)

            val boxH = paintBox.height().toFloat()
            val padding = (boxH * 0.12f).coerceIn(3f, 10f)
            val maxTextWidth = (paintBox.width().toFloat() - padding * 2f).toInt().coerceAtLeast(1)
            val maxTextHeight = (boxH - padding * 2f).coerceAtLeast(1f)
            val preferredTextSize = (boxH * 0.62f)
                .coerceIn(stableMinTextSize, stableMaxTextSize)
                .coerceAtMost(maxTextHeight * 0.92f)
                .coerceAtLeast(9f)
            val minTextSize = (preferredTextSize * 0.86f).coerceAtLeast(9f)
            textPaint.textSize = preferredTextSize
            textPaint.color = textColor
            textPaint.setShadowLayer(
                1.6f,
                0f,
                0.8f,
                if (textColor == Color.WHITE) Color.BLACK else Color.WHITE
            )

            val singleLineText = translatedText.replace(Regex("\\s+"), " ")
            val maxLines = when {
                maxTextHeight >= textPaint.textSize * 3.1f -> 3
                maxTextHeight >= textPaint.textSize * 1.9f -> 2
                else -> 1
            }
            val layout = buildBitmapTextLayout(
                text = singleLineText,
                paint = textPaint,
                width = maxTextWidth,
                maxHeight = maxTextHeight,
                maxLines = maxLines,
                minTextSize = minTextSize
            )
            val textLeft = paintBox.left.toFloat() + padding
            val textTop = paintBox.top.toFloat() + padding +
                ((maxTextHeight - layout.height).coerceAtLeast(0f) / 2f)

            // Draw text — vertically centered
            canvas.save()
            canvas.clipRect(
                textLeft,
                paintBox.top.toFloat() + padding,
                textLeft + maxTextWidth,
                paintBox.bottom.toFloat() - padding
            )
            canvas.translate(textLeft, textTop)
            layout.draw(canvas)
            canvas.restore()
        }

        return result
    }

    private fun medianPaintLineHeight(lines: List<PaintLine>, width: Int, height: Int): Float {
        val heights = lines.mapNotNull { line ->
            clippedBox(line.box, width, height).height().takeIf { it > 0 }
        }.sorted()
        if (heights.isEmpty()) return 24f

        val middle = heights.size / 2
        return if (heights.size % 2 == 0) {
            (heights[middle - 1] + heights[middle]) / 2f
        } else {
            heights[middle].toFloat()
        }
    }

    private fun expandedBox(box: Rect, width: Int, height: Int): Rect {
        val padX = (box.width() * 0.04f).toInt().coerceIn(1, 10)
        val padY = (box.height() * 0.12f).toInt().coerceIn(1, 8)
        return Rect(
            (box.left - padX).coerceAtLeast(0),
            (box.top - padY).coerceAtLeast(0),
            (box.right + padX).coerceAtMost(width),
            (box.bottom + padY).coerceAtMost(height)
        )
    }

    private fun expandedDocumentPageBox(box: Rect, width: Int, height: Int): Rect {
        val padX = (box.width() * 0.025f).toInt().coerceIn(8, 24)
        val padY = (box.height() * 0.02f).toInt().coerceIn(10, 28)
        return Rect(
            (box.left - padX).coerceAtLeast(0),
            (box.top - padY).coerceAtLeast(0),
            (box.right + padX).coerceAtMost(width),
            (box.bottom + padY).coerceAtMost(height)
        )
    }

    private fun expandedDocumentRegionBox(box: Rect, width: Int, height: Int): Rect {
        val padX = (box.width() * 0.012f).toInt().coerceIn(2, 10)
        val padY = (box.height() * 0.05f).toInt().coerceIn(2, 10)
        return Rect(
            (box.left - padX).coerceAtLeast(0),
            (box.top - padY).coerceAtLeast(0),
            (box.right + padX).coerceAtMost(width),
            (box.bottom + padY).coerceAtMost(height)
        )
    }

    private fun unionPaintLineBox(lines: List<PaintLine>): Rect? {
        if (lines.isEmpty()) return null

        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var bottom = Int.MIN_VALUE

        for (line in lines) {
            left = minOf(left, line.box.left)
            top = minOf(top, line.box.top)
            right = max(right, line.box.right)
            bottom = max(bottom, line.box.bottom)
        }

        return if (right > left && bottom > top) Rect(left, top, right, bottom) else null
    }

    private fun buildDocumentPageTextLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxHeight: Float,
        minTextSize: Float,
        maxTextSize: Float
    ): StaticLayout {
        val normalizedText = text.trim()
        var layout = createDocumentPageTextLayout(normalizedText, paint, width)
        while (layout.height > maxHeight && paint.textSize > minTextSize) {
            paint.textSize -= 0.75f
            layout = createDocumentPageTextLayout(normalizedText, paint, width)
        }
        while (layout.height < maxHeight * DOCUMENT_PAGE_TARGET_FILL_RATIO && paint.textSize < maxTextSize) {
            paint.textSize += 0.75f
            val largerLayout = createDocumentPageTextLayout(normalizedText, paint, width)
            if (largerLayout.height > maxHeight) {
                paint.textSize -= 0.75f
                return createDocumentPageTextLayout(normalizedText, paint, width)
            }
            layout = largerLayout
        }
        return layout
    }

    private fun createDocumentPageTextLayout(
        text: String,
        paint: TextPaint,
        width: Int
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.98f)
            .build()

    private fun buildBitmapTextLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxHeight: Float,
        maxLines: Int,
        minTextSize: Float
    ): StaticLayout {
        val normalizedText = text.trim()
        var layout = createBitmapTextLayout(normalizedText, paint, width, maxLines)
        while (
            (layout.height > maxHeight || layout.lineCount > maxLines) &&
            paint.textSize > minTextSize
        ) {
            paint.textSize -= 1f
            layout = createBitmapTextLayout(normalizedText, paint, width, maxLines)
        }
        return layout
    }

    private fun createBitmapTextLayout(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int
    ): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.95f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

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

    private fun sampleDocumentPaperColor(bitmap: Bitmap, box: Rect): Int {
        var r = 0L; var g = 0L; var b = 0L; var count = 0
        val left = box.left.coerceIn(0, bitmap.width - 1)
        val right = box.right.coerceIn(left, bitmap.width - 1)
        val top = box.top.coerceIn(0, bitmap.height - 1)
        val bottom = box.bottom.coerceIn(top, bitmap.height - 1)

        for (y in top..bottom step 8) {
            for (x in left..right step 8) {
                val pixel = bitmap.getPixel(x, y)
                val lum = (0.299 * Color.red(pixel) + 0.587 * Color.green(pixel) + 0.114 * Color.blue(pixel))
                if (lum < DOCUMENT_PAPER_MIN_LUMA) continue

                r += Color.red(pixel)
                g += Color.green(pixel)
                b += Color.blue(pixel)
                count++
            }
        }

        if (count == 0) return sampleBackgroundColor(bitmap, box)
        return Color.rgb(
            brightenPaperChannel((r / count).toInt()),
            brightenPaperChannel((g / count).toInt()),
            brightenPaperChannel((b / count).toInt())
        )
    }

    private fun brightenPaperChannel(value: Int): Int =
        (value * 1.08f + 8f).toInt().coerceIn(0, 255)

    /** Return white or dark text depending on background luminance. */
    private fun contrastColor(bgColor: Int): Int {
        val lum = (0.299 * Color.red(bgColor) + 0.587 * Color.green(bgColor) + 0.114 * Color.blue(bgColor))
        return if (lum > 128) Color.BLACK else Color.WHITE
    }

    fun backToLive() {
        isCaptureStarting = false
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
                captureMessage = null,
                detectedSourceLanguage = null,
                detectedSourceLanguages = emptyList(),
                qualityWarnings = emptyList()
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraTranslateEngine.release()
    }
}

private const val LOW_LIGHT_LUMA_THRESHOLD = 54f
private const val SOFT_FOCUS_THRESHOLD = 5.6f
private const val SMALL_TEXT_HEIGHT_RATIO_THRESHOLD = 0.028f
private const val SCRIPT_MISMATCH_MIN_LETTERS = 6
private const val SCRIPT_MISMATCH_MIN_CONFLICTS = 4
private const val SCRIPT_MISMATCH_RATIO_THRESHOLD = 0.55f
private const val MIXED_LINE_MIN_SCORE = 18f
private const val MIXED_LINE_OVERLAP_THRESHOLD = 0.55f
private const val TABLE_ROW_MERGE_HEIGHT_FACTOR = 0.72f
private const val TABLE_MIN_ROWS = 3
private const val TABLE_MIN_MULTI_COLUMN_ROWS = 3
private const val TABLE_MIN_MULTI_COLUMN_ROW_RATIO = 0.35f
private const val TABLE_COLUMN_GAP_HEIGHT_FACTOR = 1.35f
private const val DOCUMENT_LAYOUT_MIN_LINES = 8
private const val DOCUMENT_LAYOUT_MIN_CHARS = 220
private const val DOCUMENT_LAYOUT_MIN_HEIGHT_LINES = 6f
private const val DOCUMENT_LAYOUT_WIDE_LINE_RATIO = 0.58f
private const val DOCUMENT_REGION_GAP_HEIGHT_FACTOR = 1.18f
private const val DOCUMENT_REGION_MAX_LINES = 7
private const val DOCUMENT_REGION_MIN_TEXT_SIZE = 8.5f
private const val DOCUMENT_PAGE_TARGET_FILL_RATIO = 0.76f
private const val DOCUMENT_PAPER_MIN_LUMA = 160f
private const val GALLERY_DECODE_MAX_SIZE = 3072
private const val LIVE_TRACK_TTL_FRAMES = 5
private const val LIVE_TRANSLATION_CACHE_LIMIT = 160
private const val LIVE_FRAME_INTERVAL_MS = 450L

private val AUTO_CAPTURE_OCR_LANGUAGES = listOf(
    Language.ENGLISH,
    Language.RUSSIAN,
    Language.CHINESE_SIMPLIFIED,
    Language.HINDI
)

private val ENGLISH_HINTS = setOf("the", "and", "of", "to", "in", "is", "for", "on", "with", "exit", "entry")
private val FRENCH_HINTS = setOf("le", "la", "les", "des", "du", "et", "un", "une", "est", "pour", "avec", "sortie")
private val GERMAN_HINTS = setOf("der", "die", "das", "und", "ist", "zu", "ein", "eine", "mit", "fur", "ausgang")
private val CZECH_HINTS = setOf("je", "se", "na", "pro", "ze", "do", "nebo", "jako", "vstup")
private val SPANISH_HINTS = setOf("el", "la", "los", "las", "de", "es", "para", "con", "una", "salida")
private val PORTUGUESE_HINTS = setOf("os", "as", "de", "para", "com", "uma", "saida")
private val POLISH_HINTS = setOf("na", "do", "jest", "dla", "oraz", "nie", "wejscie")
private val TURKISH_HINTS = setOf("ve", "bir", "icin", "ile", "bu", "da", "de")
private val VIETNAMESE_HINTS = setOf("va", "la", "cua", "cho", "mot", "voi", "khong")

private val ENGLISH_CHARS = emptySet<Char>()
private val FRENCH_CHARS = setOf('\u00e0', '\u00e2', '\u00e7', '\u00e8', '\u00e9', '\u00ea', '\u00eb', '\u00ee', '\u00ef', '\u00f4', '\u00f9', '\u00fb', '\u0153', '\u00e6')
private val GERMAN_CHARS = setOf('\u00e4', '\u00f6', '\u00fc', '\u00df')
private val CZECH_CHARS = setOf('\u00e1', '\u010d', '\u010f', '\u00e9', '\u011b', '\u00ed', '\u0148', '\u00f3', '\u0159', '\u0161', '\u0165', '\u00fa', '\u016f', '\u00fd', '\u017e')
private val SPANISH_CHARS = setOf('\u00e1', '\u00e9', '\u00ed', '\u00f1', '\u00f3', '\u00fa', '\u00fc')
private val PORTUGUESE_CHARS = setOf('\u00e1', '\u00e2', '\u00e3', '\u00e0', '\u00e7', '\u00e9', '\u00ea', '\u00ed', '\u00f3', '\u00f4', '\u00f5', '\u00fa')
private val POLISH_CHARS = setOf('\u0105', '\u0107', '\u0119', '\u0142', '\u0144', '\u00f3', '\u015b', '\u017a', '\u017c')
private val TURKISH_CHARS = setOf('\u00e7', '\u011f', '\u0131', '\u00f6', '\u015f', '\u00fc')
private val VIETNAMESE_CHARS = setOf('\u0103', '\u00e2', '\u00ea', '\u00f4', '\u01a1', '\u01b0', '\u0111')
private val UKRAINIAN_CHARS = setOf('\u0456', '\u0457', '\u0454', '\u0491', '\u0406', '\u0407', '\u0404', '\u0490')

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

private fun StringBuilder.endsWithHyphen(): Boolean {
    if (isEmpty()) return false
    return this[length - 1] == '-' || this[length - 1] == '\u2010' || this[length - 1] == '\u2011'
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
