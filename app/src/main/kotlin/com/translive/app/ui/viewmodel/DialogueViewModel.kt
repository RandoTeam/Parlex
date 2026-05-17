package com.translive.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.ModelRepository
import com.translive.app.data.db.DialogueDao
import com.translive.app.data.model.DialogueSession
import com.translive.app.data.model.Language
import com.translive.app.data.model.ModelRuntime
import com.translive.app.data.SettingsRepository
import com.translive.app.engine.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.translive.app.data.model.DialogueMessage as DbDialogueMessage

/** UI-layer message (not Room entity) */
data class DialogueUiMessage(
    val sourceText: String,
    val translatedText: String,
    val sourceLang: String,  // "ru" or "en"
    val targetLang: String
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
    val isTtsReady: Boolean = true,  // System TTS always available
    val hasMicPermission: Boolean = false,
    val sourceLanguage: Language = Language.RUSSIAN,
    val targetLanguage: Language = Language.ENGLISH,
    val error: String? = null
)

@HiltViewModel
class DialogueViewModel @Inject constructor(
    private val app: Application,
    private val engine: TranslationEngine,
    private val liteRtEngine: LiteRtTranslationEngine,
    private val modelRepository: ModelRepository,
    private val settings: SettingsRepository,
    private val systemTts: SystemTtsEngine,
    private val speechEngine: SpeechEngine,
    private val dialogueDao: DialogueDao
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(DialogueUiState())
    val uiState: StateFlow<DialogueUiState> = _uiState.asStateFlow()

    /** Current session ID in Room */
    private var currentSessionId: Long? = null

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
            engine.loadModel(path, threads)
        }
    }

    private suspend fun translateWithActiveRuntime(
        sourceText: String,
        source: Language,
        target: Language
    ): String =
        if (modelRepository.getActiveRuntime() == ModelRuntime.LITERT_LM) {
            liteRtEngine.translateSafe(sourceText, source, target)
        } else {
            engine.translateSafe(sourceText, source, target)
        }

    init {
        // Initialize system TTS immediately — no download needed
        systemTts.initialize()

        viewModelScope.launch(Dispatchers.IO) {
            val sttReady = speechEngine.areModelsDownloaded()

            var modelReady = isTranslationModelLoaded()
            if (!modelReady && modelRepository.getActiveModelPath() != null) {
                modelReady = loadActiveTranslationModel()
            }

            _uiState.update {
                it.copy(
                    isTranslationModelReady = modelReady,
                    isSttReady = sttReady,
                    isTtsReady = true
                )
            }
        }

        // Observe SpeechEngine state to show RECOGNIZING phase in UI
        viewModelScope.launch {
            speechEngine.state.collect { sttState ->
                if (!_uiState.value.isConversationActive) return@collect
                when (sttState) {
                    ListeningState.PROCESSING -> _uiState.update {
                        it.copy(phase = DialoguePhase.RECOGNIZING)
                    }
                    ListeningState.LISTENING -> {
                        // Only reset to LISTENING if currently RECOGNIZING
                        // (don't override TRANSLATING or SPEAKING phases)
                        if (_uiState.value.phase == DialoguePhase.RECOGNIZING) {
                            _uiState.update { it.copy(phase = DialoguePhase.LISTENING) }
                        }
                    }
                    else -> { /* ignore IDLE, ERROR */ }
                }
            }
        }
    }

    fun setMicPermission(granted: Boolean) {
        _uiState.update { it.copy(hasMicPermission = granted) }
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

    fun startConversation() {
        if (_uiState.value.isConversationActive) return
        if (!_uiState.value.hasMicPermission) {
            _uiState.update { it.copy(error = "Нет доступа к микрофону. Разрешите в настройках.") }
            return
        }

        _uiState.update {
            it.copy(
                isConversationActive = true,
                phase = DialoguePhase.LISTENING,
                error = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. Ensure translation model is loaded
                if (!isTranslationModelLoaded()) {
                    val path = modelRepository.getActiveModelPath()
                    if (path == null) {
                        _uiState.update {
                            it.copy(
                                isConversationActive = false,
                                phase = DialoguePhase.ERROR,
                                error = "Модель перевода не скачана. Скачайте в разделе Модели."
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
                                error = "Не удалось загрузить модель перевода."
                            )
                        }
                        return@launch
                    }
                }

                // 2. Initialize STT with auto-detect for bidirectional dialogue
                // Whisper needs to recognize both languages (e.g. Russian AND English)
                if (!speechEngine.areModelsDownloaded()) {
                    _uiState.update {
                        it.copy(
                            isConversationActive = false,
                            phase = DialoguePhase.ERROR,
                            error = "STT модели не загружены или повреждены. Переустановите в разделе Модели."
                        )
                    }
                    return@launch
                }
                val ok = speechEngine.initialize("")  // Auto-detect for bidirectional
                if (!ok) {
                    _uiState.update {
                        it.copy(
                            isConversationActive = false,
                            phase = DialoguePhase.ERROR,
                            error = "Не удалось инициализировать STT. Модели повреждены — переустановите."
                        )
                    }
                    return@launch
                }

                // 3. Create a Room session
                val state = _uiState.value
                val session = DialogueSession(
                    languageA = state.sourceLanguage.code,
                    languageB = state.targetLanguage.code,
                    title = "${state.sourceLanguage.flag} ↔ ${state.targetLanguage.flag}"
                )
                currentSessionId = dialogueDao.insertSession(session)

                // 4. Start listening
                speechEngine.startListening { result ->
                    onSpeechRecognized(result)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isConversationActive = false,
                        phase = DialoguePhase.ERROR,
                        error = "Ошибка запуска: ${e.message}"
                    )
                }
            }
        }
    }

    fun stopConversation() {
        speechEngine.stopListening()
        systemTts.stop()

        // Update session timestamp
        val sessionId = currentSessionId
        if (sessionId != null) {
            viewModelScope.launch(Dispatchers.IO) {
                dialogueDao.updateSessionTime(sessionId)
            }
        }
        currentSessionId = null

        _uiState.update {
            it.copy(
                isConversationActive = false,
                phase = DialoguePhase.IDLE
            )
        }
    }

    private fun onSpeechRecognized(result: SpeechResult) {
        if (!_uiState.value.isConversationActive) return

        _uiState.update { it.copy(phase = DialoguePhase.TRANSLATING) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value

                // Bidirectional: detect spoken language and translate to the other
                val detectedLang = Language.fromCode(result.language)
                val fromLang: Language
                val toLang: Language

                if (detectedLang != null && detectedLang.code == state.targetLanguage.code) {
                    // Speaker B: spoke in target language → translate to source
                    fromLang = state.targetLanguage
                    toLang = state.sourceLanguage
                } else {
                    // Speaker A (default): spoke in source language → translate to target
                    fromLang = state.sourceLanguage
                    toLang = state.targetLanguage
                }

                val translated = translateWithActiveRuntime(
                    sourceText = result.text,
                    source = fromLang,
                    target = toLang
                ).trim()

                val uiMessage = DialogueUiMessage(
                    sourceText = result.text,
                    translatedText = translated,
                    sourceLang = fromLang.code,
                    targetLang = toLang.code
                )

                _uiState.update {
                    it.copy(
                        messages = it.messages + uiMessage,
                        phase = DialoguePhase.SPEAKING
                    )
                }

                // Save to Room
                val sessionId = currentSessionId
                if (sessionId != null) {
                    val dbMsg = DbDialogueMessage(
                        sessionId = sessionId,
                        speaker = fromLang.code.uppercase(),
                        originalText = result.text,
                        translatedText = translated,
                        originalLanguage = fromLang.code,
                        translatedLanguage = toLang.code
                    )
                    dialogueDao.insertMessage(dbMsg)
                    dialogueDao.updateSessionTime(sessionId)
                }

                // Stop microphone to prevent feedback loop (TTS → mic → Whisper → translate → TTS...)
                speechEngine.stopListening()

                // Speak translation with system TTS (suspends until done)
                systemTts.speakAndWait(translated, toLang.code)

                // Resume listening after TTS finishes
                if (_uiState.value.isConversationActive) {
                    _uiState.update { it.copy(phase = DialoguePhase.LISTENING) }
                    speechEngine.startListening { onSpeechRecognized(it) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(phase = DialoguePhase.LISTENING, error = e.message)
                }
            }
        }
    }

    fun speakMessage(text: String, langCode: String) {
        systemTts.speak(text, langCode)
    }

    override fun onCleared() {
        super.onCleared()
        speechEngine.stopListening()
        systemTts.stop()
    }
}
