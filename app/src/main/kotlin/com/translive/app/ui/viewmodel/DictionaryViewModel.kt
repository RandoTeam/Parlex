package com.translive.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.DictionaryRepository
import com.translive.app.data.model.DictionaryEntry
import com.translive.app.data.model.Language
import com.translive.app.engine.SystemTtsEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DictionaryUiState(
    val query: String = "",
    val sourceLanguage: Language = Language.ENGLISH,
    val targetLanguage: Language = Language.RUSSIAN,
    val entries: List<DictionaryEntry> = emptyList(),
    val isSearching: Boolean = false,
    val totalEntriesCount: Int = 0,
    val pairEntriesCount: Int = 0
)

@HiltViewModel
class DictionaryViewModel @Inject constructor(
    private val dictionaryRepository: DictionaryRepository,
    private val systemTts: SystemTtsEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(DictionaryUiState())
    val uiState: StateFlow<DictionaryUiState> = _uiState.asStateFlow()

    init {
        loadStats()
    }

    fun lookup(word: String, source: Language = _uiState.value.sourceLanguage, target: Language = _uiState.value.targetLanguage) {
        val trimmed = word.trim()
        _uiState.update { it.copy(query = trimmed, sourceLanguage = source, targetLanguage = target, isSearching = true) }

        if (trimmed.isBlank()) {
            _uiState.update { it.copy(entries = emptyList(), isSearching = false) }
            return
        }

        viewModelScope.launch {
            val results = dictionaryRepository.lookupWord(trimmed, source.code, target.code)
            _uiState.update { it.copy(entries = results, isSearching = false) }
        }
    }

    fun speak(text: String, langCode: String) {
        systemTts.speak(text, langCode)
    }

    fun toggleFavorite(entry: DictionaryEntry) {
        viewModelScope.launch {
            dictionaryRepository.toggleFavorite(entry)
            // Refresh results
            lookup(_uiState.value.query)
        }
    }

    fun loadStats() {
        viewModelScope.launch {
            dictionaryRepository.ensureSeeded()
            val total = dictionaryRepository.getTotalEntryCount()
            val state = _uiState.value
            val pairCount = dictionaryRepository.getEntryCount(state.sourceLanguage.code, state.targetLanguage.code)
            _uiState.update { it.copy(totalEntriesCount = total, pairEntriesCount = pairCount) }
        }
    }

    fun importTsv(tsvContent: String, source: Language, target: Language, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val count = dictionaryRepository.importFromTsv(tsvContent, source.code, target.code, isCustom = true)
            loadStats()
            onDone(count)
        }
    }
}
