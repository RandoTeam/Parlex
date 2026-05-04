package com.translive.app.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.db.TranslationDao
import com.translive.app.data.model.Language
import com.translive.app.data.model.TranslationEntry
import com.translive.app.engine.TranslationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class TranslationUiState(
    val sourceLanguage: Language = Language.RUSSIAN,
    val targetLanguage: Language = Language.ENGLISH,
    val sourceText: String = "",
    val translatedText: String = "",
    val isTranslating: Boolean = false,
    val isModelLoaded: Boolean = false,
    val isModelLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class TranslationViewModel @Inject constructor(
    private val app: Application,
    private val engine: TranslationEngine,
    private val translationDao: TranslationDao
) : AndroidViewModel(app) {

    private val _uiState = MutableStateFlow(TranslationUiState())
    val uiState: StateFlow<TranslationUiState> = _uiState.asStateFlow()

    val history = translationDao.getRecentTranslations().stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )

    val favorites = translationDao.getFavorites().stateIn(
        viewModelScope, SharingStarted.Lazily, emptyList()
    )

    fun loadModel() {
        if (_uiState.value.isModelLoaded || _uiState.value.isModelLoading) return
        _uiState.update { it.copy(isModelLoading = true, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val modelDir = File(app.filesDir, "models")
                val modelFile = File(modelDir, "hy-mt1.5-1.8b-2bit.gguf")

                if (!modelFile.exists()) {
                    _uiState.update {
                        it.copy(
                            isModelLoading = false,
                            error = "Model file not found at ${modelFile.absolutePath}. " +
                                    "Please download and place the GGUF file there."
                        )
                    }
                    return@launch
                }

                val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
                val loaded = engine.loadModel(modelFile.absolutePath, threads)

                _uiState.update {
                    it.copy(
                        isModelLoaded = loaded,
                        isModelLoading = false,
                        error = if (!loaded) "Failed to load model" else null
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isModelLoading = false, error = "Load error: ${e.message}")
                }
            }
        }
    }

    fun setSourceText(text: String) {
        _uiState.update { it.copy(sourceText = text) }
    }

    fun setSourceLanguage(lang: Language) {
        _uiState.update { it.copy(sourceLanguage = lang) }
    }

    fun setTargetLanguage(lang: Language) {
        _uiState.update { it.copy(targetLanguage = lang) }
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
        if (state.sourceText.isBlank() || !state.isModelLoaded || state.isTranslating) return

        _uiState.update { it.copy(isTranslating = true, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = engine.translate(
                    sourceText = state.sourceText,
                    source = state.sourceLanguage,
                    target = state.targetLanguage
                )

                _uiState.update {
                    it.copy(translatedText = result, isTranslating = false)
                }

                // Save to history
                translationDao.insertTranslation(
                    TranslationEntry(
                        sourceLanguage = state.sourceLanguage.code,
                        targetLanguage = state.targetLanguage.code,
                        sourceText = state.sourceText,
                        translatedText = result
                    )
                )
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isTranslating = false, error = "Translation error: ${e.message}")
                }
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
        engine.unloadModel()
    }
}
