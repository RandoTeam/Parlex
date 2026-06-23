package com.translive.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.translive.app.AppLog
import com.translive.app.R
import com.translive.app.data.ModelRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.db.TranslationDao
import com.translive.app.data.model.Language
import com.translive.app.data.model.ModelRuntime
import com.translive.app.data.model.TranslationEntry
import com.translive.app.engine.LanguageDetectionEngine
import com.translive.app.engine.LiteRtTranslationEngine
import com.translive.app.engine.TranslationEngine
import com.translive.app.engine.SystemTtsEngine
import com.translive.app.i18n.LocalizedTextProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class TranslationStats(
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val totalTimeMs: Long = 0,
    val tokensPerSecond: Float = 0f
)

data class TranslationUiState(
    val sourceLanguage: Language = Language.RUSSIAN,
    val isSourceAuto: Boolean = false,
    val detectedSourceLanguage: Language? = null,
    val isDetectingSourceLanguage: Boolean = false,
    val targetLanguage: Language = Language.ENGLISH,
    val sourceText: String = "",
    val translatedText: String = "",
    val isTranslating: Boolean = false,
    val isModelLoaded: Boolean = false,
    val isModelLoading: Boolean = false,
    val activeModelName: String? = null,
    val error: String? = null,
    val stats: TranslationStats? = null
)

@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val app: Application,
    private val engine: TranslationEngine,
    private val languageDetectionEngine: LanguageDetectionEngine,
    private val liteRtEngine: LiteRtTranslationEngine,
    private val translationDao: TranslationDao,
    private val modelRepository: ModelRepository,
    private val settings: SettingsRepository,
    val systemTts: SystemTtsEngine,
    private val texts: LocalizedTextProvider,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TranslationVM"
    }

    private fun tr(id: Int, vararg args: Any): String =
        texts.text(id, *args)

    private val _uiState = MutableStateFlow(
        TranslationUiState(
            sourceText = savedStateHandle["sourceText"] ?: "",
            translatedText = savedStateHandle["translatedText"] ?: "",
            isSourceAuto = savedStateHandle.get<Boolean>("srcAuto") ?: settings.textSourceAuto,
            sourceLanguage = savedStateHandle.get<String>("srcLang")?.let { code ->
                Language.entries.find { it.code == code }
            } ?: settings.textSourceLanguage,
            targetLanguage = savedStateHandle.get<String>("tgtLang")?.let { code ->
                Language.entries.find { it.code == code }
            } ?: settings.textTargetLanguage
        )
    )
    val uiState: StateFlow<TranslationUiState> = _uiState.asStateFlow()

    val history = translationDao.getRecentTranslations().stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )

    val favorites = translationDao.getFavorites().stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )

    /** Job for idle auto-unload timer. Reset on each translation. */
    private var idleTimerJob: Job? = null
    private var sourceDetectionJob: Job? = null

    fun loadModel() {
        if (_uiState.value.isModelLoaded || _uiState.value.isModelLoading) return
        AppLog.i(TAG, "Loading model...")
        _uiState.update { it.copy(isModelLoading = true, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val modelPath = modelRepository.getActiveModelPath()
                val activeVariant = modelRepository.getActiveVariant()

                if (modelPath == null) {
                    _uiState.update {
                        it.copy(
                            isModelLoading = false,
                            error = tr(R.string.error_no_model_selected)
                        )
                    }
                    return@launch
                }

                val runtime = modelRepository.getActiveRuntime()
                val threads = settings.threads
                val loaded = if (runtime == ModelRuntime.LITERT_LM) {
                    engine.unloadModel()
                    liteRtEngine.loadModel(modelPath, settings.backend, threads)
                } else {
                    liteRtEngine.unloadModel()
                    engine.loadModel(modelPath, threads)
                }

                AppLog.i(TAG, "Model load result: $loaded, runtime=$runtime, path=${modelPath.takeLast(40)}")

                _uiState.update {
                    it.copy(
                        isModelLoaded = loaded,
                        isModelLoading = false,
                        activeModelName = if (loaded) {
                            activeVariant?.let { variant ->
                                if (runtime == ModelRuntime.LITERT_LM) {
                                    val backend = liteRtEngine.currentBackend ?: settings.backend
                                    "${variant.quantName} Beta (${backend.uppercase()})"
                                } else {
                                    variant.quantName
                                }
                            }
                        } else null,
                        error = if (!loaded) tr(R.string.error_load_model_failed) else null
                    )
                }

                if (loaded) {
                    resetIdleTimer()
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "loadModel failed", e)
                _uiState.update {
                    it.copy(isModelLoading = false, error = tr(R.string.error_load_model_with_message, e.message ?: ""))
                }
            }
        }
    }

    fun setSourceText(text: String) {
        _uiState.update { it.copy(sourceText = text) }
        savedStateHandle["sourceText"] = text
        scheduleSourceLanguageDetection(text)
    }

    fun setSourceLanguage(lang: Language) {
        _uiState.update {
            it.copy(
                sourceLanguage = lang,
                isSourceAuto = false,
                detectedSourceLanguage = null,
                isDetectingSourceLanguage = false
            )
        }
        savedStateHandle["srcLang"] = lang.code
        savedStateHandle["srcAuto"] = false
        settings.textSourceLanguage = lang
        settings.textSourceAuto = false
        sourceDetectionJob?.cancel()
    }

    fun setSourceAuto() {
        _uiState.update {
            it.copy(
                isSourceAuto = true,
                detectedSourceLanguage = null,
                isDetectingSourceLanguage = it.sourceText.isNotBlank()
            )
        }
        savedStateHandle["srcAuto"] = true
        settings.textSourceAuto = true
        scheduleSourceLanguageDetection(_uiState.value.sourceText)
    }

    fun setTargetLanguage(lang: Language) {
        _uiState.update { it.copy(targetLanguage = lang) }
        savedStateHandle["tgtLang"] = lang.code
        settings.textTargetLanguage = lang
    }

    fun swapLanguages() {
        if (_uiState.value.isSourceAuto) return

        _uiState.update {
            it.copy(
                sourceLanguage = it.targetLanguage,
                targetLanguage = it.sourceLanguage,
                sourceText = it.translatedText,
                translatedText = it.sourceText
            )
        }
        val state = _uiState.value
        savedStateHandle["srcLang"] = state.sourceLanguage.code
        savedStateHandle["tgtLang"] = state.targetLanguage.code
        savedStateHandle["sourceText"] = state.sourceText
        savedStateHandle["translatedText"] = state.translatedText
        settings.textSourceLanguage = state.sourceLanguage
        settings.textTargetLanguage = state.targetLanguage
        settings.textSourceAuto = false
    }

    fun translate() {
        val state = _uiState.value
        if (state.sourceText.isBlank() || state.isTranslating) return

        val runtime = modelRepository.getActiveRuntime()
        val loaded = if (runtime == ModelRuntime.LITERT_LM) liteRtEngine.isLoaded else engine.isLoaded

        // Auto-reload model if it was unloaded (idle timer, other VM, etc.)
        if (!loaded) {
            _uiState.update { it.copy(isModelLoaded = false) }
            loadModel()  // will set isModelLoaded=true on success
            // Queue translation after load completes
            viewModelScope.launch {
                // Wait for model to finish loading
                _uiState.first { !it.isModelLoading }
                if (_uiState.value.isModelLoaded) {
                    translate()  // retry
                }
            }
            return
        }

        _uiState.update { it.copy(isTranslating = true, error = null, stats = null, translatedText = "") }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val effectiveSourceLanguage = resolveSourceLanguageForTranslation(state)
                val startTime = System.currentTimeMillis()
                val textBuilder = StringBuilder()
                var streamResult: TranslationEngine.StreamResult? = null

                if (runtime == ModelRuntime.LITERT_LM) {
                    liteRtEngine.translateStreaming(
                        sourceText = state.sourceText,
                        source = effectiveSourceLanguage,
                        target = state.targetLanguage
                    ).collect { token ->
                        textBuilder.append(token)
                        _uiState.update { it.copy(translatedText = textBuilder.toString().trim()) }
                    }
                } else {
                    // Acquire mutex to prevent concurrent native access (e.g. camera)
                    engine.inferenceMutex.lock()
                    try {
                        engine.translateStreaming(
                            sourceText = state.sourceText,
                            source = effectiveSourceLanguage,
                            target = state.targetLanguage,
                            onComplete = { streamResult = it }
                        ).collect { token ->
                            textBuilder.append(token)
                            val currentText = textBuilder.toString().trim()
                            _uiState.update { it.copy(translatedText = currentText) }
                        }
                    } finally {
                        engine.inferenceMutex.unlock()
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime
                val result = textBuilder.toString().trim()
                val promptTokens = streamResult?.promptTokens ?: 0
                val genTokens = streamResult?.generatedTokens ?: 0
                val tps = if (elapsed > 0) genTokens * 1000f / elapsed else 0f

                val stats = TranslationStats(
                    promptTokens = promptTokens,
                    generatedTokens = genTokens,
                    totalTimeMs = elapsed,
                    tokensPerSecond = tps
                )

                AppLog.i(TAG, "Translation done: ${effectiveSourceLanguage.code}→${state.targetLanguage.code} ${elapsed}ms ${String.format("%.1f", tps)} tok/s")

                _uiState.update {
                    it.copy(translatedText = result, isTranslating = false, stats = stats)
                }
                savedStateHandle["translatedText"] = result

                // Save to history
                translationDao.insertTranslation(
                    TranslationEntry(
                        sourceLanguage = effectiveSourceLanguage.code,
                        targetLanguage = state.targetLanguage.code,
                        sourceText = state.sourceText,
                        translatedText = result
                    )
                )

                // Reset idle timer after successful translation
                resetIdleTimer()
            } catch (e: Exception) {
                AppLog.e(TAG, "translate failed", e)
                _uiState.update {
                    it.copy(isTranslating = false, error = "Translation error: ${e.message}")
                }
            }
        }
    }

    private fun scheduleSourceLanguageDetection(text: String) {
        sourceDetectionJob?.cancel()
        if (!_uiState.value.isSourceAuto) return

        val normalized = text.trim()
        if (normalized.isBlank()) {
            _uiState.update {
                it.copy(detectedSourceLanguage = null, isDetectingSourceLanguage = false)
            }
            return
        }

        sourceDetectionJob = viewModelScope.launch {
            _uiState.update { it.copy(isDetectingSourceLanguage = true) }
            delay(350)
            val detected = withContext(Dispatchers.Default) {
                languageDetectionEngine.detect(normalized, _uiState.value.sourceLanguage)
            }
            AppLog.d(TAG, "Language detected: $detected")
            _uiState.update {
                if (it.isSourceAuto && it.sourceText.trim() == normalized) {
                    it.copy(
                        detectedSourceLanguage = detected,
                        isDetectingSourceLanguage = false
                    )
                } else {
                    it
                }
            }
        }
    }

    private suspend fun resolveSourceLanguageForTranslation(state: TranslationUiState): Language {
        if (!state.isSourceAuto) return state.sourceLanguage

        val detected = languageDetectionEngine.detect(state.sourceText, state.sourceLanguage)
        _uiState.update {
            it.copy(
                detectedSourceLanguage = detected,
                isDetectingSourceLanguage = false
            )
        }
        return detected
    }

    /**
     * Reset the idle auto-unload timer.
     * If [SettingsRepository.idleTimeoutMinutes] > 0, schedules model unload after that delay.
     */
    private fun resetIdleTimer() {
        idleTimerJob?.cancel()
        val timeoutMinutes = settings.idleTimeoutMinutes
        if (timeoutMinutes <= 0) return  // Disabled

        idleTimerJob = viewModelScope.launch {
            delay(timeoutMinutes * 60_000L)
            if (engine.isLoaded || liteRtEngine.isLoaded) {
                AppLog.i(TAG, "Idle timeout: unloading model after ${timeoutMinutes}min")
                // Update UI state FIRST to close the race window
                _uiState.update {
                    it.copy(
                        isModelLoaded = false,
                        activeModelName = null,
                        error = tr(R.string.notice_model_unloaded_idle, timeoutMinutes)
                    )
                }
                engine.unloadModel()
                liteRtEngine.unloadModel()
            }
        }
    }

    fun toggleFavorite(entry: TranslationEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            translationDao.updateTranslation(entry.copy(isFavorite = !entry.isFavorite))
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            translationDao.clearNonFavoriteHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        idleTimerJob?.cancel()
        sourceDetectionJob?.cancel()
        // Do NOT call engine.unloadModel() — engine is a @Singleton shared
        // with DialogueViewModel and CameraViewModel
    }
}
