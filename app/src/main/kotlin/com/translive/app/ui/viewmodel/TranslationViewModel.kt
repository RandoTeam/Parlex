package com.translive.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.translive.app.R
import com.translive.app.data.ModelRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.TranslationPolicy
import com.translive.app.data.db.TranslationDao
import com.translive.app.data.model.Language
import com.translive.app.data.model.ModelRuntime
import com.translive.app.data.model.TranslationEntry
import com.translive.app.engine.LanguageDetectionEngine
import com.translive.app.engine.LiteRtTranslationEngine
import com.translive.app.engine.FastTranslateEngine
import com.translive.app.engine.TransliterationEngine
import com.translive.app.engine.TranslationEngine
import com.translive.app.engine.SystemTtsEngine
import com.translive.app.engine.CurrencyAugmentor
import com.translive.app.i18n.LocalizedTextProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject

import com.translive.app.data.DictionaryRepository
import com.translive.app.data.model.DictionaryEntry

data class TranslationStats(
    val promptTokens: Int = 0,
    val generatedTokens: Int = 0,
    val totalTimeMs: Long = 0,
    val tokensPerSecond: Float = 0f,
    val backend: String? = null,
    val hasNativeTokenMetrics: Boolean = false
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
    val llmImproveUnavailableReason: String? = null,
    val stats: TranslationStats? = null,
    val isFastResult: Boolean = false,
    val canImproveWithLlm: Boolean = false,
    val isImprovingWithLlm: Boolean = false,
    val fastTranslationText: String = "",
    val fastNmtMissing: Boolean = false,
    val sourceTransliteration: String? = null,
    val targetTransliteration: String? = null,
    val dictionaryEntries: List<DictionaryEntry> = emptyList()
)

@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val app: Application,
    private val engine: TranslationEngine,
    private val languageDetectionEngine: LanguageDetectionEngine,
    private val liteRtEngine: LiteRtTranslationEngine,
    private val fastTranslateEngine: FastTranslateEngine,
    private val transliterationEngine: TransliterationEngine,
    private val dictionaryRepository: DictionaryRepository,
    private val currencyAugmentor: CurrencyAugmentor,
    private val modelRepository: ModelRepository,
    private val settings: SettingsRepository,
    private val translationDao: TranslationDao,
    val systemTts: SystemTtsEngine,
    private val texts: LocalizedTextProvider,
    private val savedStateHandle: SavedStateHandle
) : AndroidViewModel(app) {

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

        when (settings.translationMode) {
            SettingsRepository.TRANSLATION_MODE_FAST_ONLY -> {
                _uiState.update {
                    it.copy(
                        isModelLoaded = false,
                        isModelLoading = false,
                        error = null,
                        llmImproveUnavailableReason = null
                    )
                }
            }
            SettingsRepository.TRANSLATION_MODE_FAST_IMPROVE -> {
                val modelPath = modelRepository.getActiveModelPath()
                val activeVariant = modelRepository.getActiveVariant()

                if (modelPath == null) {
                    _uiState.update {
                        it.copy(
                            isModelLoaded = false,
                            isModelLoading = false,
                            error = null,
                            llmImproveUnavailableReason = "Улучшенный перевод недоступен без скачанной LLM-модели"
                        )
                    }
                    return
                }

                _uiState.update { it.copy(isModelLoading = true, error = null, llmImproveUnavailableReason = null) }

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val runtime = modelRepository.getActiveRuntime()
                        val threads = settings.threads
                        val loaded = if (runtime == ModelRuntime.LITERT_LM) {
                            engine.unloadModel()
                            liteRtEngine.loadModel(modelPath, settings.backend, threads)
                        } else {
                            liteRtEngine.unloadModel()
                            engine.loadModel(modelPath, threads, settings.backend)
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
                                error = null,
                                llmImproveUnavailableReason = if (!loaded) "Улучшенный перевод недоступен: не удалось инициализировать LLM" else null
                            )
                        }

                        if (loaded) {
                            resetIdleTimer()
                        }
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                isModelLoading = false,
                                error = null,
                                llmImproveUnavailableReason = "Улучшенный перевод недоступен: ${e.message}"
                            )
                        }
                    }
                }
            }
            SettingsRepository.TRANSLATION_MODE_LLM_DIRECT -> {
                _uiState.update { it.copy(isModelLoading = true, error = null, llmImproveUnavailableReason = null) }

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val modelPath = modelRepository.getActiveModelPath()
                        val activeVariant = modelRepository.getActiveVariant()

                        if (modelPath == null) {
                            _uiState.update {
                                it.copy(
                                    isModelLoaded = false,
                                    isModelLoading = false,
                                    error = tr(R.string.error_no_model_selected),
                                    llmImproveUnavailableReason = null
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
                            engine.loadModel(modelPath, threads, settings.backend)
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
                                error = if (!loaded) tr(R.string.error_load_model_failed) else null,
                                llmImproveUnavailableReason = null
                            )
                        }

                        if (loaded) {
                            resetIdleTimer()
                        }
                    } catch (e: Exception) {
                        _uiState.update {
                            it.copy(
                                isModelLoading = false,
                                error = tr(R.string.error_load_model_with_message, e.message ?: ""),
                                llmImproveUnavailableReason = null
                            )
                        }
                    }
                }
            }
        }
    }

    fun setSourceText(text: String) {
        val srcTrans = if (settings.showTransliteration) transliterationEngine.transliterate(text, _uiState.value.sourceLanguage) else null
        _uiState.update { it.copy(sourceText = text, sourceTransliteration = srcTrans) }
        savedStateHandle["sourceText"] = text
        scheduleSourceLanguageDetection(text)

        val trimmed = text.trim()
        if (trimmed.isNotBlank() && !trimmed.contains(" ") && trimmed.length <= 40) {
            viewModelScope.launch {
                val entries = dictionaryRepository.lookupWord(
                    rawWord = trimmed,
                    sourceLang = _uiState.value.sourceLanguage.code,
                    targetLang = _uiState.value.targetLanguage.code
                )
                _uiState.update { it.copy(dictionaryEntries = entries) }
            }
        } else {
            _uiState.update { it.copy(dictionaryEntries = emptyList()) }
        }
    }

    fun lookupDictionaryWord(word: String) {
        viewModelScope.launch {
            val entries = dictionaryRepository.lookupWord(
                rawWord = word,
                sourceLang = _uiState.value.targetLanguage.code,
                targetLang = _uiState.value.sourceLanguage.code
            )
            _uiState.update { it.copy(dictionaryEntries = entries) }
        }
    }

    fun dismissDictionaryPopup() {
        _uiState.update { it.copy(dictionaryEntries = emptyList()) }
    }

    fun toggleDictionaryFavorite(entry: DictionaryEntry) {
        viewModelScope.launch {
            dictionaryRepository.toggleFavorite(entry)
            val updated = _uiState.value.dictionaryEntries.map {
                if (it.id == entry.id) it.copy(isFavorite = !it.isFavorite) else it
            }
            _uiState.update { it.copy(dictionaryEntries = updated) }
        }
    }

    fun speakDictionaryWord(word: String, langCode: String) {
        systemTts.speak(word, langCode)
    }

    fun shouldHideKeyboardOnTranslate(): Boolean = settings.hideKeyboardOnTextTranslate

    fun shouldShowTechnicalTranslationStats(): Boolean = settings.showTechnicalTranslationStats

    fun shouldShowTransliteration(): Boolean = settings.showTransliteration

    fun setSourceLanguage(lang: Language) {
        val srcTrans = if (settings.showTransliteration) transliterationEngine.transliterate(_uiState.value.sourceText, lang) else null
        _uiState.update {
            it.copy(
                sourceLanguage = lang,
                sourceTransliteration = srcTrans,
                isSourceAuto = false,
                detectedSourceLanguage = null,
                isDetectingSourceLanguage = false,
                translatedText = "",
                targetTransliteration = null,
                stats = null,
                isFastResult = false,
                canImproveWithLlm = false,
                isImprovingWithLlm = false,
                fastTranslationText = "",
                error = null
            )
        }
        savedStateHandle["srcLang"] = lang.code
        savedStateHandle["srcAuto"] = false
        savedStateHandle["translatedText"] = ""
        settings.textSourceLanguage = lang
        settings.textSourceAuto = false
        sourceDetectionJob?.cancel()

        updateDictionaryLookupInternal(_uiState.value.sourceText, lang.code, _uiState.value.targetLanguage.code)
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
        _uiState.update {
            it.copy(
                targetLanguage = lang,
                translatedText = "",
                targetTransliteration = null,
                stats = null,
                isFastResult = false,
                canImproveWithLlm = false,
                isImprovingWithLlm = false,
                fastTranslationText = "",
                error = null
            )
        }
        savedStateHandle["tgtLang"] = lang.code
        savedStateHandle["translatedText"] = ""
        settings.textTargetLanguage = lang

        updateDictionaryLookupInternal(_uiState.value.sourceText, _uiState.value.sourceLanguage.code, lang.code)
    }

    fun swapLanguages() {
        val oldState = _uiState.value
        val effectiveSource = if (oldState.isSourceAuto) {
            oldState.detectedSourceLanguage ?: return
        } else {
            oldState.sourceLanguage
        }

        val newSourceLang = oldState.targetLanguage
        val newTargetLang = effectiveSource

        val hasTranslation = oldState.translatedText.isNotBlank()
        val newSourceText = if (hasTranslation) oldState.translatedText else oldState.sourceText
        val newTranslatedText = if (hasTranslation) oldState.sourceText else ""

        val newSrcTrans = if (settings.showTransliteration && newSourceText.isNotBlank()) {
            transliterationEngine.transliterate(newSourceText, newSourceLang)
        } else null
        val newTgtTrans = if (settings.showTransliteration && newTranslatedText.isNotBlank()) {
            transliterationEngine.transliterate(newTranslatedText, newTargetLang)
        } else null

        _uiState.update {
            it.copy(
                sourceLanguage = newSourceLang,
                targetLanguage = newTargetLang,
                isSourceAuto = false,
                detectedSourceLanguage = null,
                isDetectingSourceLanguage = false,
                sourceText = newSourceText,
                translatedText = newTranslatedText,
                sourceTransliteration = newSrcTrans,
                targetTransliteration = newTgtTrans,
                stats = null,
                isFastResult = false,
                canImproveWithLlm = false,
                isImprovingWithLlm = false,
                fastTranslationText = "",
                error = null
            )
        }
        val state = _uiState.value
        savedStateHandle["srcLang"] = state.sourceLanguage.code
        savedStateHandle["tgtLang"] = state.targetLanguage.code
        savedStateHandle["srcAuto"] = false
        savedStateHandle["sourceText"] = state.sourceText
        savedStateHandle["translatedText"] = state.translatedText
        settings.textSourceLanguage = state.sourceLanguage
        settings.textTargetLanguage = state.targetLanguage
        settings.textSourceAuto = false

        updateDictionaryLookupInternal(newSourceText, newSourceLang.code, newTargetLang.code)
    }

    private fun updateDictionaryLookupInternal(text: String, srcLangCode: String, tgtLangCode: String) {
        val trimmed = text.trim()
        if (trimmed.isNotBlank() && !trimmed.contains(" ") && trimmed.length <= 40) {
            viewModelScope.launch {
                val entries = dictionaryRepository.lookupWord(
                    rawWord = trimmed,
                    sourceLang = srcLangCode,
                    targetLang = tgtLangCode
                )
                _uiState.update { it.copy(dictionaryEntries = entries) }
            }
        } else {
            _uiState.update { it.copy(dictionaryEntries = emptyList()) }
        }
    }


    fun translate() {
        val state = _uiState.value
        if (state.sourceText.isBlank() || state.isTranslating) return

        val policy = settings.translationPolicy

        when (policy) {
            TranslationPolicy.FAST -> translateFast()
            TranslationPolicy.FAST_WITH_LLM_IMPROVE -> translateFastWithImproveOption()
            TranslationPolicy.LLM_ONLY -> translateWithLlm()
        }
    }

    private fun translateFast() {
        val state = _uiState.value
        _uiState.update {
            it.copy(isTranslating = true, error = null, stats = null, translatedText = "",
                isFastResult = false, canImproveWithLlm = false, isImprovingWithLlm = false,
                fastTranslationText = "", fastNmtMissing = false, targetTransliteration = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val effectiveSource = resolveSourceLanguageForTranslation(state)
                val activated = fastTranslateEngine.activateDownloadedPair(
                    effectiveSource.code, state.targetLanguage.code
                )
                if (!activated) {
                    _uiState.update {
                        it.copy(isTranslating = false, fastNmtMissing = true,
                            error = "Install fast translation packages for this language pair in Models")
                    }
                    return@launch
                }
                val startTime = System.currentTimeMillis()
                val rawResult = fastTranslateEngine.translate(state.sourceText)
                val result = currencyAugmentor.augment(rawResult, effectiveSource)
                val elapsed = System.currentTimeMillis() - startTime
                val tgtTrans = if (settings.showTransliteration) transliterationEngine.transliterate(result, state.targetLanguage) else null
                _uiState.update {
                    it.copy(translatedText = result, targetTransliteration = tgtTrans, isTranslating = false,
                        isFastResult = true, canImproveWithLlm = false,
                        stats = TranslationStats(totalTimeMs = elapsed))
                }
                savedStateHandle["translatedText"] = result
                translationDao.insertTranslation(
                    TranslationEntry(
                        sourceLanguage = effectiveSource.code,
                        targetLanguage = state.targetLanguage.code,
                        sourceText = state.sourceText,
                        translatedText = result
                    )
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTranslating = false, error = "Fast translation error: ${e.message}")
                }
            }
        }
    }

    private fun translateFastWithImproveOption() {
        val state = _uiState.value
        _uiState.update {
            it.copy(isTranslating = true, error = null, stats = null, translatedText = "",
                isFastResult = false, canImproveWithLlm = false, isImprovingWithLlm = false,
                fastTranslationText = "", fastNmtMissing = false)
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val effectiveSource = resolveSourceLanguageForTranslation(state)
                val activated = fastTranslateEngine.activateDownloadedPair(
                    effectiveSource.code, state.targetLanguage.code
                )
                if (activated) {
                    val startTime = System.currentTimeMillis()
                    val rawResult = fastTranslateEngine.translate(state.sourceText)
                    val fastResult = currencyAugmentor.augment(rawResult, effectiveSource)
                    val elapsed = System.currentTimeMillis() - startTime
                    val tgtTrans = if (settings.showTransliteration) transliterationEngine.transliterate(fastResult, state.targetLanguage) else null
                    _uiState.update {
                        it.copy(translatedText = fastResult, targetTransliteration = tgtTrans, isTranslating = false,
                            isFastResult = true, canImproveWithLlm = true,
                            fastTranslationText = fastResult,
                            stats = TranslationStats(totalTimeMs = elapsed))
                    }
                    savedStateHandle["translatedText"] = fastResult
                    translationDao.insertTranslation(
                        TranslationEntry(
                            sourceLanguage = effectiveSource.code,
                            targetLanguage = state.targetLanguage.code,
                            sourceText = state.sourceText,
                            translatedText = fastResult
                        )
                    )
                } else {
                    // NMT not available — fall through to LLM directly
                    translateWithLlm()
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTranslating = false, error = "Translation error: ${e.message}")
                }
            }
        }
    }

    fun improveWithLlm() {
        val state = _uiState.value
        if (state.isImprovingWithLlm || state.sourceText.isBlank()) return

        _uiState.update { it.copy(isImprovingWithLlm = true, canImproveWithLlm = false, error = null) }

        val runtime = modelRepository.getActiveRuntime()
        val loaded = if (runtime == ModelRuntime.LITERT_LM) liteRtEngine.isLoaded else engine.isLoaded

        if (!loaded) {
            loadModel()
            viewModelScope.launch {
                _uiState.first { !it.isModelLoading }
                if (_uiState.value.isModelLoaded) {
                    improveWithLlm()
                } else {
                    _uiState.update { it.copy(isImprovingWithLlm = false, canImproveWithLlm = true) }
                }
            }
            return
        }

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
                    engine.inferenceMutex.lock()
                    try {
                        engine.translateStreaming(
                            sourceText = state.sourceText,
                            source = effectiveSourceLanguage,
                            target = state.targetLanguage,
                            onComplete = { streamResult = it }
                        ).collect { token ->
                            textBuilder.append(token)
                            _uiState.update { it.copy(translatedText = textBuilder.toString().trim()) }
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
                val tgtTrans = if (settings.showTransliteration) transliterationEngine.transliterate(result, state.targetLanguage) else null

                _uiState.update {
                    it.copy(
                        translatedText = result,
                        targetTransliteration = tgtTrans,
                        isImprovingWithLlm = false,
                        isFastResult = false,
                        canImproveWithLlm = false,
                        stats = TranslationStats(
                            promptTokens = promptTokens,
                            generatedTokens = genTokens,
                            totalTimeMs = elapsed,
                            tokensPerSecond = tps,
                            backend = if (runtime == ModelRuntime.LITERT_LM) liteRtEngine.currentBackend else engine.currentBackend,
                            hasNativeTokenMetrics = runtime != ModelRuntime.LITERT_LM
                        )
                    )
                }
                savedStateHandle["translatedText"] = result
                resetIdleTimer()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isImprovingWithLlm = false, canImproveWithLlm = true,
                        error = "LLM improvement error: ${e.message}")
                }
            }
        }
    }

    private fun translateWithLlm() {
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

        _uiState.update { it.copy(isTranslating = true, error = null, stats = null, translatedText = "",
            isFastResult = false, canImproveWithLlm = false, isImprovingWithLlm = false, fastTranslationText = "", fastNmtMissing = false) }

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
                val tgtTrans = if (settings.showTransliteration) transliterationEngine.transliterate(result, state.targetLanguage) else null

                val stats = TranslationStats(
                    promptTokens = promptTokens,
                    generatedTokens = genTokens,
                    totalTimeMs = elapsed,
                    tokensPerSecond = tps,
                    backend = if (runtime == ModelRuntime.LITERT_LM) {
                        liteRtEngine.currentBackend
                    } else {
                        engine.currentBackend
                    },
                    hasNativeTokenMetrics = runtime != ModelRuntime.LITERT_LM
                )

                _uiState.update {
                    it.copy(translatedText = result, targetTransliteration = tgtTrans, isTranslating = false, stats = stats)
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
                // Update UI state FIRST to close the race window
                _uiState.update {
                    it.copy(
                        isModelLoaded = false,
                        activeModelName = null,
                        error = null
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
