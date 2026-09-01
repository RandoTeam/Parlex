package com.translive.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.db.DialogueDao
import com.translive.app.data.db.TranslationDao
import com.translive.app.data.model.DialogueMessage
import com.translive.app.data.model.DialogueSession
import com.translive.app.data.model.DialogueSessionStats
import com.translive.app.data.model.TranslationEntry
import com.translive.app.engine.dialogue.AudioPlaybackState
import com.translive.app.engine.dialogue.DialogueAudioPlayer
import com.translive.app.engine.dialogue.DialogueSummaryEngine
import com.translive.app.engine.dialogue.SummaryUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HistoryTab { ALL, FAVORITES, VOICE }

data class HistoryUiState(
    val tab: HistoryTab = HistoryTab.ALL,
    val searchQuery: String = "",
    val languageFilter: String? = null,
    val translations: List<TranslationEntry> = emptyList(),
    val voiceSessions: List<DialogueSession> = emptyList(),
    val selectedSession: DialogueSession? = null,
    val selectedSessionMessages: List<DialogueMessage> = emptyList(),
    val selectedSessionStats: DialogueSessionStats = DialogueSessionStats(),
    val audioState: AudioPlaybackState = AudioPlaybackState(),
    val summaryState: SummaryUiState = SummaryUiState.Idle,
    val selectedSessionId: Long? = null,
    val favoriteVoiceMessages: List<DialogueMessage> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val translationDao: TranslationDao,
    private val dialogueDao: DialogueDao,
    private val audioPlayer: DialogueAudioPlayer,
    private val summaryEngine: DialogueSummaryEngine
) : ViewModel() {

    private val _tab = MutableStateFlow(HistoryTab.ALL)
    private val _searchQuery = MutableStateFlow("")
    private val _languageFilter = MutableStateFlow<String?>(null)
    private val _selectedSessionId = MutableStateFlow<Long?>(null)
    private val _summaryState = MutableStateFlow<SummaryUiState>(SummaryUiState.Idle)

    private var summaryGenerationJob: Job? = null

    private val allTranslations = translationDao.getAllTranslations()
    private val favoriteTranslations = translationDao.getFavorites()
    private val voiceSessions = dialogueDao.getAllSessions()
    private val favoriteVoiceMessages = dialogueDao.getFavoriteMessages()

    private val selectedSessionFlow = _selectedSessionId.flatMapLatest { id ->
        if (id != null) dialogueDao.getSessionById(id) else flowOf(null)
    }

    private val selectedSessionMessagesFlow = _selectedSessionId.flatMapLatest { id ->
        if (id != null) dialogueDao.getMessagesForSession(id) else flowOf(emptyList())
    }

    val uiState: StateFlow<HistoryUiState> = combine(
        _tab,
        _searchQuery,
        _languageFilter,
        allTranslations,
        favoriteTranslations
    ) { tab, query, filter, all, favs ->
        val filteredTranslations = when (tab) {
            HistoryTab.ALL -> all
            HistoryTab.FAVORITES -> favs
            HistoryTab.VOICE -> emptyList()
        }.filter { entry ->
            val langMatch = if (filter != null) {
                "${entry.sourceLanguage}-${entry.targetLanguage}" == filter ||
                    "${entry.targetLanguage}-${entry.sourceLanguage}" == filter
            } else true
            val queryMatch = if (query.isNotBlank()) {
                entry.sourceText.contains(query, ignoreCase = true) ||
                    entry.translatedText.contains(query, ignoreCase = true)
            } else true
            langMatch && queryMatch
        }
        HistoryUiState(
            tab = tab,
            searchQuery = query,
            languageFilter = filter,
            translations = filteredTranslations
        )
    }.combine(voiceSessions) { state, sessions ->
        state.copy(voiceSessions = sessions)
    }.combine(selectedSessionFlow) { state, session ->
        state.copy(selectedSession = session)
    }.combine(selectedSessionMessagesFlow) { state, messages ->
        val stats = DialogueSessionStats.fromMessages(messages, state.selectedSession?.durationMs?.takeIf { it > 0 })
        state.copy(
            selectedSessionMessages = messages,
            selectedSessionStats = stats,
            selectedSessionId = _selectedSessionId.value
        )
    }.combine(audioPlayer.playbackState) { state, audio ->
        state.copy(audioState = audio)
    }.combine(_summaryState) { state, summary ->
        state.copy(summaryState = summary)
    }.combine(favoriteVoiceMessages) { state, favVoice ->
        state.copy(favoriteVoiceMessages = favVoice)
    }.stateIn(viewModelScope, SharingStarted.Lazily, HistoryUiState())

    fun setTab(tab: HistoryTab) {
        _tab.value = tab
        selectSession(null)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setLanguageFilter(filter: String?) {
        _languageFilter.value = filter
    }

    fun selectSession(sessionId: Long?) {
        _selectedSessionId.value = sessionId
        audioPlayer.pause()
        summaryGenerationJob?.cancel()

        if (sessionId == null) {
            audioPlayer.loadSessionAudio(null, emptyList())
            _summaryState.value = SummaryUiState.Idle
        } else {
            viewModelScope.launch {
                val session = dialogueDao.getSessionById(sessionId).firstOrNull()
                val messages = dialogueDao.getMessagesForSession(sessionId).firstOrNull() ?: emptyList()
                audioPlayer.loadSessionAudio(session?.audioFilePath, messages)

                if (!session?.summary.isNullOrBlank()) {
                    _summaryState.value = SummaryUiState.Success(
                        session?.summary ?: "",
                        session?.summaryTimestamp ?: session?.updatedAt ?: 0L
                    )
                } else {
                    _summaryState.value = SummaryUiState.Idle
                }
            }
        }
    }

    fun toggleAudioPlayPause() {
        audioPlayer.togglePlayPause()
    }

    fun seekAudio(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    fun cyclePlaybackSpeed() {
        audioPlayer.cyclePlaybackSpeed()
    }

    fun seekToTurn(message: DialogueMessage) {
        audioPlayer.seekToTurn(message)
    }

    fun generateAiSummary() {
        val session = uiState.value.selectedSession ?: return
        val messages = uiState.value.selectedSessionMessages
        if (messages.isEmpty()) return

        summaryGenerationJob?.cancel()
        summaryGenerationJob = viewModelScope.launch {
            summaryEngine.generateSummaryStreaming(session, messages).collect { state ->
                _summaryState.value = state
            }
        }
    }

    fun clearSummary() {
        val sessionId = uiState.value.selectedSession?.id ?: return
        viewModelScope.launch {
            summaryEngine.clearSummary(sessionId)
            _summaryState.value = SummaryUiState.Idle
        }
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
            if (_selectedSessionId.value == session.id) {
                selectSession(null)
            }
            dialogueDao.deleteMessagesForSession(session.id)
            dialogueDao.deleteSession(session)
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            translationDao.clearNonFavoriteHistory()
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}
