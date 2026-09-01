package com.translive.app.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.R
import com.translive.app.data.ModelRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.TranslationPolicy
import com.translive.app.data.db.DialogueDao
import com.translive.app.data.model.DialogueMessage as DbDialogueMessage
import com.translive.app.data.model.DialogueSession
import com.translive.app.data.model.Language
import com.translive.app.data.model.ModelRuntime
import com.translive.app.engine.*
import com.translive.app.engine.dialogue.DialogueLanguageArbiter
import com.translive.app.engine.dialogue.DialogueSessionPair
import com.translive.app.engine.dialogue.DialogueTurnContext
import com.translive.app.i18n.LocalizedTextProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** UI-layer message (not Room entity) */
data class DialogueUiMessage(
    val sourceText: String,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val sourceTransliteration: String? = null,
    val targetTransliteration: String? = null,
    val isLlmRefined: Boolean = false,
    val isImproving: Boolean = false,
    val dbMessageId: Long? = null
)

enum class DialoguePhase {
    IDLE,
    LISTENING,
    RECOGNIZING,
    TRANSLATING,
    SPEAKING,
    ERROR
}

data class DialogueUiState(
    val messages: List<DialogueUiMessage> = emptyList(),
    val phase: DialoguePhase = DialoguePhase.IDLE,
    val isConversationActive: Boolean = false,
    val isTranslationModelReady: Boolean = false,
    val isSttReady: Boolean = false,
    val isTtsReady: Boolean = true,
    val isAutoSpeakEnabled: Boolean = true,
    val hasMicPermission: Boolean = false,
    val sourceLanguage: Language = Language.RUSSIAN,
    val targetLanguage: Language = Language.ENGLISH,
    val error: String? = null
)

private data class DialogueTranslationResult(
    val text: String,
    val isLlm: Boolean
)

@HiltViewModel
class DialogueViewModel @Inject constructor(
    private val app: Application,
    private val fastTranslateEngine: FastTranslateEngine,
    private val transliterationEngine: TransliterationEngine,
    private val engine: TranslationEngine,
    private val liteRtEngine: LiteRtTranslationEngine,
    private val modelRepository: ModelRepository,
    private val settings: SettingsRepository,
    private val systemTts: SystemTtsEngine,
    private val speechEngine: SpeechEngine,
    private val dialogueDao: DialogueDao,
    private val arbiter: DialogueLanguageArbiter,
    private val texts: LocalizedTextProvider,
    private val audioRecorder: com.translive.app.engine.audio.DialogueAudioRecorder
) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "DialogueViewModel"
        private const val REVERB_COOLDOWN_MS = 300L
    }

    private fun tr(id: Int, vararg args: Any): String =
        texts.text(id, *args)

    private val _uiState = MutableStateFlow(
        DialogueUiState(
            sourceLanguage = settings.dialogueSourceLanguage,
            targetLanguage = settings.dialogueTargetLanguage,
            isAutoSpeakEnabled = settings.dialogueAutoSpeak
        )
    )
    val uiState: StateFlow<DialogueUiState> = _uiState.asStateFlow()

    fun toggleAutoSpeak() {
        val newValue = !_uiState.value.isAutoSpeakEnabled
        settings.dialogueAutoSpeak = newValue
        _uiState.update { it.copy(isAutoSpeakEnabled = newValue) }
    }

    /** Current session ID in Room */
    private var currentSessionId: Long? = null
    private var turnContext = DialogueTurnContext.EMPTY
    private val turnMutex = Mutex()
    private var sessionStartTimeMs = 0L
    private var totalWordsCount = 0
    private var totalCharactersCount = 0
    private var turnIndexCounter = 0

    init {
        systemTts.initialize()
        refreshReadiness()

        // Bridge SpeechEngine state to UI phase
        viewModelScope.launch {
            speechEngine.state.collect { sttState ->
                if (!_uiState.value.isConversationActive) return@collect
                when (sttState) {
                    ListeningState.PROCESSING -> {
                        if (_uiState.value.phase == DialoguePhase.LISTENING) {
                            _uiState.update { it.copy(phase = DialoguePhase.RECOGNIZING) }
                        }
                    }
                    ListeningState.LISTENING -> {
                        if (_uiState.value.phase == DialoguePhase.RECOGNIZING) {
                            _uiState.update { it.copy(phase = DialoguePhase.LISTENING) }
                        }
                    }
                    ListeningState.ERROR -> {
                        _uiState.update { it.copy(phase = DialoguePhase.ERROR, error = tr(R.string.error_stt_init_failed)) }
                    }
                    else -> Unit
                }
            }
        }
    }

    fun refreshReadiness() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = _uiState.value
            val sttReady = speechEngine.areModelsDownloaded()
            val translationReady = checkTranslationReadiness(state.sourceLanguage, state.targetLanguage)

            _uiState.update {
                it.copy(
                    isTranslationModelReady = translationReady,
                    isSttReady = sttReady,
                    isTtsReady = true
                )
            }
        }
    }

    private suspend fun checkTranslationReadiness(source: Language, target: Language): Boolean {
        val policy = settings.translationPolicy
        if (policy == TranslationPolicy.FAST || policy == TranslationPolicy.FAST_WITH_LLM_IMPROVE) {
            val status = fastTranslateEngine.getPackageStatus(source.code, target.code)
            if (status.isReady) return true
        }
        return isTranslationModelLoaded() || modelRepository.getActiveModelPath() != null
    }

    private fun isTranslationModelLoaded(): Boolean =
        if (modelRepository.getActiveRuntime() == ModelRuntime.LITERT_LM) {
            liteRtEngine.isLoaded
        } else {
            engine.isLoaded
        }

    private fun loadActiveTranslationModel(): Boolean {
        val path = modelRepository.getActiveModelPath() ?: return false
        val threads = settings.threads
        return if (modelRepository.getActiveRuntime() == ModelRuntime.LITERT_LM) {
            engine.unloadModel()
            liteRtEngine.loadModel(path, settings.backend, threads)
        } else {
            liteRtEngine.unloadModel()
            engine.loadModel(path, threads, settings.backend)
        }
    }

    private suspend fun translateWithActiveRuntime(
        sourceText: String,
        source: Language,
        target: Language
    ): DialogueTranslationResult {
        val policy = settings.translationPolicy
        if (policy == TranslationPolicy.FAST || policy == TranslationPolicy.FAST_WITH_LLM_IMPROVE) {
            val activated = fastTranslateEngine.activateDownloadedPair(source.code, target.code)
            if (activated) {
                val translated = fastTranslateEngine.translate(sourceText)
                return DialogueTranslationResult(text = translated, isLlm = false)
            }
        }

        val translated = if (modelRepository.getActiveRuntime() == ModelRuntime.LITERT_LM) {
            liteRtEngine.translateSafe(sourceText, source, target)
        } else {
            engine.translateSafe(sourceText, source, target)
        }
        return DialogueTranslationResult(text = translated, isLlm = true)
    }

    fun setMicPermission(granted: Boolean) {
        _uiState.update { it.copy(hasMicPermission = granted) }
    }

    fun setSourceLanguage(lang: Language) {
        _uiState.update { it.copy(sourceLanguage = lang) }
        settings.dialogueSourceLanguage = lang
        refreshReadiness()
    }

    fun setTargetLanguage(lang: Language) {
        _uiState.update { it.copy(targetLanguage = lang) }
        settings.dialogueTargetLanguage = lang
        refreshReadiness()
    }

    fun swapLanguages() {
        _uiState.update {
            it.copy(sourceLanguage = it.targetLanguage, targetLanguage = it.sourceLanguage)
        }
        val state = _uiState.value
        settings.dialogueSourceLanguage = state.sourceLanguage
        settings.dialogueTargetLanguage = state.targetLanguage
        refreshReadiness()
    }

    fun startConversation() {
        if (_uiState.value.isConversationActive) return
        if (!_uiState.value.hasMicPermission) {
            _uiState.update { it.copy(error = tr(R.string.error_no_mic_permission)) }
            return
        }

        val state = _uiState.value

        _uiState.update {
            it.copy(
                isConversationActive = true,
                phase = DialoguePhase.LISTENING,
                error = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fastActivated = fastTranslateEngine.activateDownloadedPair(
                    state.sourceLanguage.code,
                    state.targetLanguage.code
                )

                if (!fastActivated) {
                    if (!isTranslationModelLoaded()) {
                        val path = modelRepository.getActiveModelPath()
                        if (path == null) {
                            _uiState.update {
                                it.copy(
                                    isConversationActive = false,
                                    phase = DialoguePhase.ERROR,
                                    error = tr(R.string.error_translation_model_missing)
                                )
                            }
                            return@launch
                        }
                        val loaded = loadActiveTranslationModel()
                        if (!loaded) {
                            _uiState.update {
                                it.copy(
                                    isConversationActive = false,
                                    phase = DialoguePhase.ERROR,
                                    error = tr(R.string.error_translation_model_load_failed)
                                )
                            }
                            return@launch
                        }
                    }
                }

                if (!speechEngine.areModelsDownloaded()) {
                    _uiState.update {
                        it.copy(
                            isConversationActive = false,
                            phase = DialoguePhase.ERROR,
                            error = tr(R.string.error_stt_missing_or_corrupt)
                        )
                    }
                    return@launch
                }

                // Setup Audio Recording if enabled
                val recordingEnabled = settings.dialogueRecordingEnabled
                val format = settings.dialogueAudioFormat
                var recordedFile: java.io.File? = null

                if (recordingEnabled) {
                    recordedFile = audioRecorder.startSession(format)
                    speechEngine.onPcmSamplesRead = { samples, read ->
                        audioRecorder.writePcmSamples(samples, read)
                    }
                } else {
                    speechEngine.onPcmSamplesRead = null
                }

                sessionStartTimeMs = System.currentTimeMillis()
                totalWordsCount = 0
                totalCharactersCount = 0
                turnIndexCounter = 0

                // Zero-Emoji Material 3 Room Session Title (e.g., "Russian - English")
                val session = DialogueSession(
                    languageA = state.sourceLanguage.code,
                    languageB = state.targetLanguage.code,
                    title = "${state.sourceLanguage.displayName} - ${state.targetLanguage.displayName}",
                    createdAt = sessionStartTimeMs,
                    updatedAt = sessionStartTimeMs,
                    isRecorded = recordingEnabled,
                    audioFilePath = recordedFile?.absolutePath,
                    audioFormat = if (recordingEnabled) format.id else null
                )
                currentSessionId = dialogueDao.insertSession(session)
                turnContext = DialogueTurnContext.EMPTY

                // Launch continuous bidirectional hands-free listening loop
                speechEngine.startListening(language = "", singleShot = false) { result ->
                    processDialogueTurn(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start continuous dialogue", e)
                _uiState.update {
                    it.copy(
                        isConversationActive = false,
                        phase = DialoguePhase.ERROR,
                        error = tr(R.string.error_start_with_message, e.message ?: "")
                    )
                }
            }
        }
    }

    fun stopConversation() {
        speechEngine.stopListening()
        speechEngine.onPcmSamplesRead = null
        systemTts.stop()

        val recordingResult = audioRecorder.stopSession()
        val sessionId = currentSessionId
        if (sessionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val totalDuration = recordingResult?.durationMs ?: (System.currentTimeMillis() - sessionStartTimeMs).coerceAtLeast(0L)
                dialogueDao.updateSessionStatistics(
                    sessionId = sessionId,
                    durationMs = totalDuration,
                    totalTurns = turnIndexCounter,
                    totalWords = totalWordsCount,
                    totalCharacters = totalCharactersCount,
                    updatedAt = System.currentTimeMillis()
                )
            }
        }
        currentSessionId = null
        turnContext = DialogueTurnContext.EMPTY

        _uiState.update {
            it.copy(
                isConversationActive = false,
                phase = DialoguePhase.IDLE
            )
        }
    }

    /**
     * Serialized turn processor leveraging DialogueLanguageArbiter, AEC guard, and auto-resume.
     */
    private fun processDialogueTurn(result: SpeechResult) {
        if (!_uiState.value.isConversationActive) return

        viewModelScope.launch(Dispatchers.IO) {
            turnMutex.withLock {
                try {
                    val rawText = result.text.trim()
                    if (rawText.isBlank()) return@withLock

                    _uiState.update { it.copy(phase = DialoguePhase.TRANSLATING) }

                    val state = _uiState.value
                    val pair = DialogueSessionPair(
                        primaryLanguage = state.sourceLanguage,
                        secondaryLanguage = state.targetLanguage
                    )

                    // 1. Resolve spoken and target language via deterministic arbiter
                    val arbitration = arbiter.arbitrate(
                        spokenText = rawText,
                        pair = pair,
                        context = turnContext
                    )

                    val fromLang = arbitration.resolvedLanguage
                    val toLang = arbitration.targetLanguage

                    Log.d(TAG, "Arbitrated utterance '$rawText' -> ${fromLang.code} to ${toLang.code} via ${arbitration.resolutionMethod} (conf=${arbitration.confidence})")

                    // 2. Perform translation (Fast NMT first ~20ms, or LLM directly if configured/fallback)
                    val translationResult = translateWithActiveRuntime(
                        sourceText = rawText,
                        source = fromLang,
                        target = toLang
                    )
                    val translated = translationResult.text.trim()
                    val isLlm = translationResult.isLlm

                    val srcTrans = if (settings.showTransliteration) transliterationEngine.transliterate(rawText, fromLang) else null
                    val tgtTrans = if (settings.showTransliteration) transliterationEngine.transliterate(translated, toLang) else null

                    // 3. Save zero-emoji record to Room database with audio timing & stats
                    val wordsInTurn = com.translive.app.data.model.DialogueSessionStats.countWords(rawText)
                    val charsInTurn = rawText.length
                    totalWordsCount += wordsInTurn
                    totalCharactersCount += charsInTurn
                    turnIndexCounter++

                    val currentOffset = audioRecorder.getRelativeOffsetMs()
                    val sessionId = currentSessionId
                    var insertedDbId: Long? = null
                    if (sessionId != null) {
                        val dbMsg = DbDialogueMessage(
                            sessionId = sessionId,
                            speaker = fromLang.code.uppercase(),
                            originalText = rawText,
                            translatedText = translated,
                            originalLanguage = fromLang.code,
                            translatedLanguage = toLang.code,
                            timestamp = System.currentTimeMillis(),
                            audioStartTimeMs = currentOffset,
                            audioDurationMs = 2500L,
                            wordCount = wordsInTurn,
                            characterCount = charsInTurn
                        )
                        insertedDbId = dialogueDao.insertMessage(dbMsg)
                        dialogueDao.updateSessionStatistics(
                            sessionId = sessionId,
                            durationMs = (System.currentTimeMillis() - sessionStartTimeMs).coerceAtLeast(0L),
                            totalTurns = turnIndexCounter,
                            totalWords = totalWordsCount,
                            totalCharacters = totalCharactersCount
                        )
                    }

                    // 4. Update UI message list
                    val uiMessage = DialogueUiMessage(
                        sourceText = rawText,
                        translatedText = translated,
                        sourceLang = fromLang.code,
                        targetLang = toLang.code,
                        sourceTransliteration = srcTrans,
                        targetTransliteration = tgtTrans,
                        isLlmRefined = isLlm,
                        isImproving = false,
                        dbMessageId = insertedDbId
                    )

                    val autoSpeak = _uiState.value.isAutoSpeakEnabled

                    _uiState.update {
                        it.copy(
                            messages = it.messages + uiMessage,
                            phase = if (autoSpeak) DialoguePhase.SPEAKING else DialoguePhase.LISTENING
                        )
                    }

                    // 5. Conditional TTS Playback with Hardware AEC frame suppression
                    if (autoSpeak) {
                        speechEngine.notifyTtsPlaybackStarted()
                        systemTts.speakAndWait(translated, toLang.code)
                        speechEngine.notifyTtsPlaybackFinished()

                        // 6. Post-TTS acoustic reverb cooldown
                        delay(REVERB_COOLDOWN_MS)
                        speechEngine.resetVad()
                    }

                    // 7. Advance turn context for alternation prior heuristics
                    turnContext = DialogueTurnContext(
                        previousLanguage = fromLang,
                        isSameSpeaker = false,
                        turnIndex = turnContext.turnIndex + 1
                    )

                    // 8. Auto-Listening loop continuation: Return to LISTENING phase
                    if (_uiState.value.isConversationActive) {
                        _uiState.update { it.copy(phase = DialoguePhase.LISTENING) }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in continuous dialogue turn", e)
                    speechEngine.notifyTtsPlaybackFinished()
                    speechEngine.resetVad()
                    if (_uiState.value.isConversationActive) {
                        _uiState.update { it.copy(phase = DialoguePhase.LISTENING, error = e.message) }
                    }
                }
            }
        }
    }

    /**
     * Sub-phase D1.3: Asynchronously upgrade a Fast NMT message in-place using the active LLM engine.
     */
    fun improveMessageWithLlm(messageIndex: Int) {
        val currentMessages = _uiState.value.messages
        if (messageIndex !in currentMessages.indices) return
        val targetMessage = currentMessages[messageIndex]
        if (targetMessage.isImproving || targetMessage.isLlmRefined || targetMessage.sourceText.isBlank()) return

        _uiState.update { state ->
            val updated = state.messages.toMutableList()
            if (messageIndex in updated.indices) {
                updated[messageIndex] = updated[messageIndex].copy(isImproving = true)
            }
            state.copy(messages = updated, error = null)
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!isTranslationModelLoaded()) {
                    val loaded = loadActiveTranslationModel()
                    if (!loaded) {
                        _uiState.update { state ->
                            val updated = state.messages.toMutableList()
                            if (messageIndex in updated.indices) {
                                updated[messageIndex] = updated[messageIndex].copy(isImproving = false)
                            }
                            state.copy(messages = updated, error = tr(R.string.error_translation_model_load_failed))
                        }
                        return@launch
                    }
                }

                val srcLang = Language.fromCode(targetMessage.sourceLang) ?: Language.RUSSIAN
                val tgtLang = Language.fromCode(targetMessage.targetLang) ?: Language.ENGLISH

                val refinedText = if (modelRepository.getActiveRuntime() == ModelRuntime.LITERT_LM) {
                    liteRtEngine.translateSafe(targetMessage.sourceText, srcLang, tgtLang)
                } else {
                    engine.translateSafe(targetMessage.sourceText, srcLang, tgtLang)
                }.trim()

                val tgtTrans = if (settings.showTransliteration) {
                    transliterationEngine.transliterate(refinedText, tgtLang)
                } else null

                _uiState.update { state ->
                    val updated = state.messages.toMutableList()
                    if (messageIndex in updated.indices) {
                        updated[messageIndex] = updated[messageIndex].copy(
                            translatedText = refinedText,
                            targetTransliteration = tgtTrans,
                            isLlmRefined = true,
                            isImproving = false
                        )
                    }
                    state.copy(messages = updated)
                }

                val dbId = targetMessage.dbMessageId
                val sessId = currentSessionId
                if (dbId != null && sessId != null) {
                    val dbMsg = DbDialogueMessage(
                        id = dbId,
                        sessionId = sessId,
                        speaker = targetMessage.sourceLang.uppercase(),
                        originalText = targetMessage.sourceText,
                        translatedText = refinedText,
                        originalLanguage = targetMessage.sourceLang,
                        translatedLanguage = targetMessage.targetLang
                    )
                    dialogueDao.updateMessage(dbMsg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to improve message with LLM at index $messageIndex", e)
                _uiState.update { state ->
                    val updated = state.messages.toMutableList()
                    if (messageIndex in updated.indices) {
                        updated[messageIndex] = updated[messageIndex].copy(isImproving = false)
                    }
                    state.copy(messages = updated, error = e.message)
                }
            }
        }
    }

    fun shouldShowTransliteration(): Boolean = settings.showTransliteration

    fun speakMessage(text: String, langCode: String) {
        systemTts.speak(text, langCode)
    }

    override fun onCleared() {
        super.onCleared()
        speechEngine.stopListening()
        systemTts.stop()
    }
}
