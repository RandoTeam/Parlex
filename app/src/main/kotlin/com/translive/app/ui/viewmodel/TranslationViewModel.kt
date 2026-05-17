package com.translive.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.translive.app.data.ModelRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.db.TranslationDao
import com.translive.app.data.model.Language
import com.translive.app.data.model.ModelRuntime
import com.translive.app.data.model.TranslationEntry
import com.translive.app.engine.LiteRtTranslationEngine
import com.translive.app.engine.TranslationEngine
import com.translive.app.engine.SystemTtsEngine
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
    private val liteRtEngine: LiteRtTranslationEngine,
    private val translationDao: TranslationDao,
    private val modelRepository: ModelRepository,
    private val settings: SettingsRepository,
    val systemTts: SystemTtsEngine,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(
        TranslationUiState(
            sourceText = savedStateHandle["sourceText"] ?: "",
            translatedText = savedStateHandle["translatedText"] ?: "",
            sourceLanguage = savedStateHandle.get<String>("srcLang")?.let { code ->
                Language.entries.find { it.code == code }
            } ?: Language.RUSSIAN,
            targetLanguage = savedStateHandle.get<String>("tgtLang")?.let { code ->
                Language.entries.find { it.code == code }
            } ?: Language.ENGLISH
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

    fun loadModel() {
        if (_uiState.value.isModelLoaded || _uiState.value.isModelLoading) return
        _uiState.update { it.copy(isModelLoading = true, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val modelPath = modelRepository.getActiveModelPath()
                val activeVariant = modelRepository.getActiveVariant()

                if (modelPath == null) {
                    _uiState.update {
                        it.copy(
                            isModelLoading = false,
                            error = "Модель не выбрана. Откройте вкладку 'Модели' для скачивания."
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
                        error = if (!loaded) "Не удалось загрузить модель" else null
                    )
                }

                if (loaded) {
                    resetIdleTimer()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isModelLoading = false, error = "Ошибка загрузки: ${e.message}")
                }
            }
        }
    }

    fun setSourceText(text: String) {
        _uiState.update { it.copy(sourceText = text) }
        savedStateHandle["sourceText"] = text
    }

    fun setSourceLanguage(lang: Language) {
        _uiState.update { it.copy(sourceLanguage = lang) }
        savedStateHandle["srcLang"] = lang.code
    }

    fun setTargetLanguage(lang: Language) {
        _uiState.update { it.copy(targetLanguage = lang) }
        savedStateHandle["tgtLang"] = lang.code
    }

    fun swapLanguages() {
        _uiState.update {
            it.copy(
                sourceLanguage = it.targetLanguage,
                targetLanguage = it.sourceLanguage,
                sourceText = it.translatedText,
                translatedText = it.sourceText
            )
        }
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
                val startTime = System.currentTimeMillis()
                val textBuilder = StringBuilder()
                var streamResult: TranslationEngine.StreamResult? = null

                if (runtime == ModelRuntime.LITERT_LM) {
                    liteRtEngine.translateStreaming(
                        sourceText = state.sourceText,
                        source = state.sourceLanguage,
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
                            source = state.sourceLanguage,
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

                _uiState.update {
                    it.copy(translatedText = result, isTranslating = false, stats = stats)
                }
                savedStateHandle["translatedText"] = result

                // Save to history
                translationDao.insertTranslation(
                    TranslationEntry(
                        sourceLanguage = state.sourceLanguage.code,
                        targetLanguage = state.targetLanguage.code,
                        sourceText = state.sourceText,
                        translatedText = result
                    )
                )

                // Reset idle timer after successful translation
                resetIdleTimer()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTranslating = false, error = "Translation error: ${e.message}")
                }
            }
        }
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
                // Update UI state FIRST to close the race window
                _uiState.update {
                    it.copy(
                        isModelLoaded = false,
                        activeModelName = null,
                        error = "Модель выгружена (простой ${timeoutMinutes} мин.)"
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
        // Do NOT call engine.unloadModel() — engine is a @Singleton shared
        // with DialogueViewModel and CameraViewModel
    }
}
