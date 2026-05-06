package com.translive.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.ModelRepository
import com.translive.app.data.db.DialogueDao
import com.translive.app.data.model.DialogueSession
import com.translive.app.data.model.Language
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
    val sourceLanguage: Language = Language.RUSSIAN,
    val targetLanguage: Language = Language.ENGLISH,
    val error: String? = null
)

@HiltViewModel
class DialogueViewModel @Inject constructor(
    private val app: Application,
    private val engine: TranslationEngine,
    private val modelRepository: ModelRepository,
    private val systemTts: SystemTtsEngine,
    private val speechEngine: SpeechEngine,
    private val dialogueDao: DialogueDao
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(DialogueUiState())
    val uiState: StateFlow<DialogueUiState> = _uiState.asStateFlow()

    /** Current session ID in Room */
    private var currentSessionId: Long? = null

    init {
        // Initialize system TTS immediately — no download needed
        systemTts.initialize()

        viewModelScope.launch {
            val activeId = modelRepository.getActiveModelId()
            _uiState.update {
                it.copy(
                    isTranslationModelReady = activeId != null,
                    isSttReady = speechEngine.areModelsDownloaded(),
                    isTtsReady = true  // System TTS is always ready
                )
            }
        }
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

        _uiState.update {
            it.copy(
                isConversationActive = true,
                phase = DialoguePhase.LISTENING,
                error = null
            )
        }

        // Initialize STT then start listening (sequential, no race condition)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!speechEngine.isReady.value) {
                    val ok = speechEngine.initialize()
                    if (!ok) {
                        _uiState.update {
                            it.copy(
                                isConversationActive = false,
                                phase = DialoguePhase.ERROR,
                                error = "STT модели не загружены. Скачайте в разделе Модели."
                            )
                        }
                        return@launch
                    }
                }

                // Create a Room session
                val state = _uiState.value
                val session = DialogueSession(
                    languageA = state.sourceLanguage.code,
                    languageB = state.targetLanguage.code,
                    title = "${state.sourceLanguage.flag} ↔ ${state.targetLanguage.flag}"
                )
                currentSessionId = dialogueDao.insertSession(session)

                // Now safe to start listening
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
                val fromLang: Language
                val toLang: Language

                if (result.language == state.sourceLanguage.code) {
                    fromLang = state.sourceLanguage
                    toLang = state.targetLanguage
                } else {
                    fromLang = state.targetLanguage
                    toLang = state.sourceLanguage
                }

                val translated = engine.translateSafe(
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

                // Speak translation with system TTS (suspends until done)
                systemTts.speakAndWait(translated, toLang.code)

                // Resume listening
                if (_uiState.value.isConversationActive) {
                    _uiState.update { it.copy(phase = DialoguePhase.LISTENING) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(phase = DialoguePhase.LISTENING, error = e.message)
                }
            }
        }
    }

    fun speakMessage(text: String) {
        val state = _uiState.value
        systemTts.speak(text, state.targetLanguage.code)
    }

    override fun onCleared() {
        super.onCleared()
        speechEngine.stopListening()
        systemTts.stop()
    }
}
