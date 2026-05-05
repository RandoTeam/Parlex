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
    val isTtsReady: Boolean = false,
    val sourceLanguage: Language = Language.RUSSIAN,
    val targetLanguage: Language = Language.ENGLISH,
    val error: String? = null
)

@HiltViewModel
class DialogueViewModel @Inject constructor(
    private val app: Application,
    private val engine: TranslationEngine,
    private val modelRepository: ModelRepository,
    private val ttsEngine: TtsEngine,
    private val speechEngine: SpeechEngine,
    private val dialogueDao: DialogueDao
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(DialogueUiState())
    val uiState: StateFlow<DialogueUiState> = _uiState.asStateFlow()

    val ttsState: StateFlow<TtsState> = ttsEngine.state

    /** Current session ID in Room */
    private var currentSessionId: Long? = null

    init {
        viewModelScope.launch {
            val activeId = modelRepository.getActiveModelId()
            _uiState.update {
                it.copy(
                    isTranslationModelReady = activeId != null,
                    isSttReady = speechEngine.areModelsDownloaded(),
                    isTtsReady = ttsEngine.isModelDownloaded()
                )
            }
        }
    }

    fun startConversation() {
        if (_uiState.value.isConversationActive) return

        // Initialize engines
        viewModelScope.launch(Dispatchers.IO) {
            if (!speechEngine.isReady.value) {
                speechEngine.initialize()
            }
            if (!ttsEngine.isModelReady.value) {
                ttsEngine.loadModel()
            }

            // Create a Room session
            val state = _uiState.value
            val session = DialogueSession(
                languageA = state.sourceLanguage.code,
                languageB = state.targetLanguage.code,
                title = "${state.sourceLanguage.flag} ↔ ${state.targetLanguage.flag}"
            )
            currentSessionId = dialogueDao.insertSession(session)
        }

        _uiState.update {
            it.copy(
                isConversationActive = true,
                phase = DialoguePhase.LISTENING,
                error = null
            )
        }

        speechEngine.startListening { result ->
            onSpeechRecognized(result)
        }
    }

    fun stopConversation() {
        speechEngine.stopListening()
        ttsEngine.stop()

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

                if (result.language == "ru") {
                    fromLang = state.sourceLanguage
                    toLang = state.targetLanguage
                } else {
                    fromLang = state.targetLanguage
                    toLang = state.sourceLanguage
                }

                val translated = engine.translate(
                    sourceText = result.text,
                    source = fromLang,
                    target = toLang
                ).trim()

                val uiMessage = DialogueUiMessage(
                    sourceText = result.text,
                    translatedText = translated,
                    sourceLang = result.language,
                    targetLang = if (result.language == "ru") "en" else "ru"
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
                        speaker = result.language.uppercase(),
                        originalText = result.text,
                        translatedText = translated,
                        originalLanguage = result.language,
                        translatedLanguage = if (result.language == "ru") "en" else "ru"
                    )
                    dialogueDao.insertMessage(dbMsg)
                    dialogueDao.updateSessionTime(sessionId)
                }

                // Speak the translation
                if (ttsEngine.isModelReady.value) {
                    ttsEngine.speak(translated)
                    ttsEngine.state.first { it != TtsState.SPEAKING }
                }

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
        if (ttsEngine.state.value == TtsState.SPEAKING) {
            ttsEngine.stop()
        } else {
            ttsEngine.speak(text)
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        speechEngine.stopListening()
        ttsEngine.stop()
    }
}
