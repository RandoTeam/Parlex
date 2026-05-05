package com.translive.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.db.DialogueDao
import com.translive.app.data.db.TranslationDao
import com.translive.app.data.model.DialogueMessage
import com.translive.app.data.model.DialogueSession
import com.translive.app.data.model.TranslationEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HistoryTab { ALL, FAVORITES, VOICE }

data class HistoryUiState(
    val tab: HistoryTab = HistoryTab.ALL,
    val searchQuery: String = "",
    val languageFilter: String? = null,  // null = all, "ru-en", "ru-zh" etc.
    val translations: List<TranslationEntry> = emptyList(),
    val voiceSessions: List<DialogueSession> = emptyList(),
    val selectedSessionMessages: List<DialogueMessage> = emptyList(),
    val selectedSessionId: Long? = null,
    val favoriteVoiceMessages: List<DialogueMessage> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val translationDao: TranslationDao,
    private val dialogueDao: DialogueDao
) : ViewModel() {

    private val _tab = MutableStateFlow(HistoryTab.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _languageFilter = MutableStateFlow<String?>(null)
    private val _selectedSessionId = MutableStateFlow<Long?>(null)

    private val allTranslations = translationDao.getAllTranslations()
    private val favoriteTranslations = translationDao.getFavorites()
    private val voiceSessions = dialogueDao.getAllSessions()
    private val favoriteVoiceMessages = dialogueDao.getFavoriteMessages()

    private val selectedSessionMessages = _selectedSessionId.flatMapLatest { sessionId ->
        if (sessionId != null) dialogueDao.getMessagesForSession(sessionId)
        else flowOf(emptyList())
    }

    val uiState: StateFlow<HistoryUiState> = combine(
        _tab,
        _searchQuery,
        _languageFilter,
        allTranslations,
        favoriteTranslations,
    ) { tab, query, langFilter, all, favs ->
        val baseList = when (tab) {
            HistoryTab.ALL -> all
            HistoryTab.FAVORITES -> favs
            HistoryTab.VOICE -> emptyList() // voice handled separately
        }

        val filtered = baseList
            .filter { entry ->
                if (langFilter != null) {
                    val pair = "${entry.sourceLanguage}-${entry.targetLanguage}"
                    val pairReverse = "${entry.targetLanguage}-${entry.sourceLanguage}"
                    pair == langFilter || pairReverse == langFilter
                } else true
            }
            .filter { entry ->
                if (query.isBlank()) true
                else entry.sourceText.contains(query, ignoreCase = true) ||
                        entry.translatedText.contains(query, ignoreCase = true)
            }

        HistoryUiState(
            tab = tab,
            searchQuery = query,
            languageFilter = langFilter,
            translations = filtered
        )
    }.combine(voiceSessions) { state, sessions ->
        state.copy(voiceSessions = sessions)
    }.combine(selectedSessionMessages) { state, msgs ->
        state.copy(
            selectedSessionMessages = msgs,
            selectedSessionId = _selectedSessionId.value
        )
    }.combine(favoriteVoiceMessages) { state, favMsgs ->
        state.copy(favoriteVoiceMessages = favMsgs)
    }.stateIn(viewModelScope, SharingStarted.Lazily, HistoryUiState())

    fun setTab(tab: HistoryTab) {
        _tab.value = tab
        _selectedSessionId.value = null
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setLanguageFilter(filter: String?) {
        _languageFilter.value = filter
    }

    fun selectSession(sessionId: Long?) {
        _selectedSessionId.value = sessionId
    }

    fun toggleFavorite(entry: TranslationEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            translationDao.updateTranslation(entry.copy(isFavorite = !entry.isFavorite))
        }
    }

    fun toggleVoiceFavorite(message: DialogueMessage) {
        viewModelScope.launch(Dispatchers.IO) {
            dialogueDao.updateMessage(message.copy(isFavorite = !message.isFavorite))
        }
    }

    fun deleteTranslation(entry: TranslationEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            translationDao.deleteById(entry.id)
        }
    }

    fun deleteSession(session: DialogueSession) {
        viewModelScope.launch(Dispatchers.IO) {
            dialogueDao.deleteMessagesForSession(session.id)
            dialogueDao.deleteSession(session)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            translationDao.clearNonFavoriteHistory()
        }
    }
}
