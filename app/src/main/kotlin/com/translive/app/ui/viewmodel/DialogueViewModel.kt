package com.translive.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.ModelRepository
import com.translive.app.data.model.Language
import com.translive.app.engine.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DialogueMessage(
    val sourceText: String,
    val translatedText: String,
    val sourceLang: String,  // "ru" or "en"
    val targetLang: String
)

enum class DialoguePhase {
    IDLE,        // Not started
    LISTENING,   // Microphone active, waiting for speech
    RECOGNIZING, // VAD detected speech end, running Whisper
    TRANSLATING, // Translating recognized text
    SPEAKING,    // Playing TTS
    ERROR
}

data class DialogueUiState(
    val messages: List<DialogueMessage> = emptyList(),
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
    private val speechEngine: SpeechEngine
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(DialogueUiState())
    val uiState: StateFlow<DialogueUiState> = _uiState.asStateFlow()

    val ttsState: StateFlow<TtsState> = ttsEngine.state

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
                // Determine translation direction
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

                // Translate
                val translated = engine.translate(
                    sourceText = result.text,
                    source = fromLang,
                    target = toLang
                ).trim()

                val message = DialogueMessage(
                    sourceText = result.text,
                    translatedText = translated,
                    sourceLang = result.language,
                    targetLang = if (result.language == "ru") "en" else "ru"
                )

                _uiState.update {
                    it.copy(
                        messages = it.messages + message,
                        phase = DialoguePhase.SPEAKING
                    )
                }

                // Speak the translation
                if (ttsEngine.isModelReady.value) {
                    ttsEngine.speak(translated)
                    // Wait for TTS to finish before resuming listening
                    ttsEngine.state.first { it != TtsState.SPEAKING }
                }

                // Resume listening state
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
