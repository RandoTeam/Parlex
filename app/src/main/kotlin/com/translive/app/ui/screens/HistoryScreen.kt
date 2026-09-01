package com.translive.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.R
import com.translive.app.data.model.DialogueMessage
import com.translive.app.data.model.DialogueSession
import com.translive.app.data.model.TranslationEntry
import com.translive.app.ui.components.AppBottomNavigation
import com.translive.app.ui.components.BottomNavDestination
import com.translive.app.ui.components.history.DialogueAudioPlayerBar
import com.translive.app.ui.components.history.DialogueLlmSummaryCard
import com.translive.app.ui.components.history.DialogueSessionStatsCard
import com.translive.app.ui.viewmodel.HistoryTab
import com.translive.app.ui.viewmodel.HistoryViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(
    onNavigateToTranslate: () -> Unit,
    onNavigateToDialogue: () -> Unit,
    onNavigateToCamera: () -> Unit = {},
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        bottomBar = {
            AppBottomNavigation(
                selected = BottomNavDestination.HISTORY,
                onNavigateToTranslate = onNavigateToTranslate,
                onNavigateToDialogue = onNavigateToDialogue,
                onNavigateToCamera = onNavigateToCamera,
                onNavigateToHistory = {},
                onNavigateToModels = onNavigateToModels,
                onNavigateToSettings = onNavigateToSettings
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.selectedSessionId != null) {
                        IconButton(
                            onClick = { viewModel.selectSession(null) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = if (uiState.selectedSessionId != null) {
                            uiState.selectedSession?.title?.ifEmpty { stringResource(R.string.history_voice_session) }
                                ?: stringResource(R.string.history_voice_session)
                        } else {
                            stringResource(R.string.nav_history)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Tabs (hidden when viewing specific session timeline)
            if (uiState.selectedSessionId == null) {
                TabRow(
                    selectedTabIndex = uiState.tab.ordinal,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = uiState.tab == HistoryTab.ALL,
                        onClick = { viewModel.setTab(HistoryTab.ALL) },
                        text = { Text(stringResource(R.string.history_tab_all)) },
                        icon = { Icon(Icons.Outlined.List, null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = uiState.tab == HistoryTab.FAVORITES,
                        onClick = { viewModel.setTab(HistoryTab.FAVORITES) },
                        text = { Text(stringResource(R.string.history_tab_favorites)) },
                        icon = { Icon(Icons.Outlined.Star, null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = uiState.tab == HistoryTab.VOICE,
                        onClick = { viewModel.setTab(HistoryTab.VOICE) },
                        text = { Text(stringResource(R.string.history_tab_voice)) },
                        icon = { Icon(Icons.Outlined.RecordVoiceOver, null, modifier = Modifier.size(18.dp)) }
                    )
                }

                // Search bar
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text(stringResource(R.string.history_search_placeholder)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Filled.Close, stringResource(R.string.cd_clear))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                // Language filter chips
                if (uiState.tab != HistoryTab.VOICE) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = uiState.languageFilter == null,
                            onClick = { viewModel.setLanguageFilter(null) },
                            label = { Text(stringResource(R.string.history_tab_all)) }
                        )
                        FilterChip(
                            selected = uiState.languageFilter == "ru-en",
                            onClick = {
                                viewModel.setLanguageFilter(
                                    if (uiState.languageFilter == "ru-en") null else "ru-en"
                                )
                            },
                            label = { Text("RU ↔ EN") }
                        )
                        FilterChip(
                            selected = uiState.languageFilter == "ru-zh",
                            onClick = {
                                viewModel.setLanguageFilter(
                                    if (uiState.languageFilter == "ru-zh") null else "ru-zh"
                                )
                            },
                            label = { Text("RU ↔ ZH") }
                        )
                    }
                }
            }

            // Content Area
            when {
                // Detailed Dialogue Timeline View
                uiState.selectedSessionId != null -> {
                    DialogueTimelineDetailView(
                        uiState = uiState,
                        onTogglePlayPause = viewModel::toggleAudioPlayPause,
                        onSeek = viewModel::seekAudio,
                        onCycleSpeed = viewModel::cyclePlaybackSpeed,
                        onSeekToTurn = viewModel::seekToTurn,
                        onGenerateSummary = viewModel::generateAiSummary,
                        onToggleFavorite = viewModel::toggleVoiceFavorite,
                        onCopy = { text -> clipboardManager.setText(AnnotatedString(text)) }
                    )
                }

                // Voice Sessions List
                uiState.tab == HistoryTab.VOICE -> {
                    val filteredSessions = if (uiState.searchQuery.isBlank()) {
                        uiState.voiceSessions
                    } else {
                        uiState.voiceSessions.filter { it.title.contains(uiState.searchQuery, ignoreCase = true) }
                    }

                    if (filteredSessions.isEmpty()) {
                        EmptyState(
                            icon = Icons.Outlined.RecordVoiceOver,
                            message = stringResource(R.string.history_no_voice_sessions)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredSessions, key = { it.id }) { session ->
                                SessionCard(
                                    session = session,
                                    onClick = { viewModel.selectSession(session.id) },
                                    onDelete = { viewModel.deleteSession(session) }
                                )
                            }
                        }
                    }
                }

                // Favorites or All Translations List
                else -> {
                    if (uiState.translations.isEmpty() && (uiState.tab != HistoryTab.FAVORITES || uiState.favoriteVoiceMessages.isEmpty())) {
                        EmptyState(
                            icon = if (uiState.tab == HistoryTab.FAVORITES) Icons.Outlined.Star else Icons.Outlined.History,
                            message = if (uiState.tab == HistoryTab.FAVORITES)
                                stringResource(R.string.history_no_favorites)
                            else
                                stringResource(R.string.history_no_translations)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.translations, key = { "t_${it.id}" }) { entry ->
                                HistoryCard(
                                    entry = entry,
                                    onToggleFavorite = { viewModel.toggleFavorite(entry) },
                                    onCopy = {
                                        clipboardManager.setText(AnnotatedString(entry.translatedText))
                                    },
                                    onDelete = { viewModel.deleteTranslation(entry) }
                                )
                            }

                            if (uiState.tab == HistoryTab.FAVORITES && uiState.favoriteVoiceMessages.isNotEmpty()) {
                                item {
                                    Text(
                                        text = stringResource(R.string.history_tab_voice),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                                    )
                                }
                                items(uiState.favoriteVoiceMessages, key = { "vm_${it.id}" }) { msg ->
                                    VoiceMessageCard(
                                        message = msg,
                                        onToggleFavorite = { viewModel.toggleVoiceFavorite(msg) },
                                        onCopy = {
                                            clipboardManager.setText(AnnotatedString(msg.translatedText))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogueTimelineDetailView(
    uiState: com.translive.app.ui.viewmodel.HistoryUiState,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onCycleSpeed: () -> Unit,
    onSeekToTurn: (DialogueMessage) -> Unit,
    onGenerateSummary: () -> Unit,
    onToggleFavorite: (DialogueMessage) -> Unit,
    onCopy: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Session Analytics Header Card
        item {
            DialogueSessionStatsCard(stats = uiState.selectedSessionStats)
        }

        // 2. Synchronized Audio Player Bar (if session was recorded)
        if (uiState.audioState.isReady) {
            item {
                DialogueAudioPlayerBar(
                    audioState = uiState.audioState,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeek = onSeek,
                    onCycleSpeed = onCycleSpeed
                )
            }
        }

        // 3. Local LiteRT Gemma 2 LLM Session Summary Card
        item {
            DialogueLlmSummaryCard(
                summaryState = uiState.summaryState,
                onGenerateClicked = onGenerateSummary
            )
        }

        // 4. Chronological Turns Timeline Header
        item {
            Text(
                text = stringResource(R.string.history_stats_turns),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // 5. Chronological Turn Cards
        items(uiState.selectedSessionMessages, key = { it.id }) { message ->
            val isPlayingThisTurn = uiState.audioState.activeTurnId == message.id
            ChronologicalTurnCard(
                message = message,
                isPlaying = isPlayingThisTurn,
                hasAudio = uiState.audioState.isReady,
                onPlayTurn = { onSeekToTurn(message) },
                onToggleFavorite = { onToggleFavorite(message) },
                onCopy = { onCopy(message.translatedText) }
            )
        }
    }
}

@Composable
private fun ChronologicalTurnCard(
    message: DialogueMessage,
    isPlaying: Boolean,
    hasAudio: Boolean,
    onPlayTurn: () -> Unit,
    onToggleFavorite: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    val srcBadge = "[${message.originalLanguage.uppercase(Locale.ROOT)}]"
    val tgtBadge = "[${message.translatedLanguage.uppercase(Locale.ROOT)}]"

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isPlaying -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                message.isFavorite -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$srcBadge → $tgtBadge",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (message.wordCount > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${message.wordCount} w",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            fontSize = 10.sp
                        )
                    }
                }
                Text(
                    text = formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = message.originalText,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = message.translatedText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasAudio) {
                    IconButton(
                        onClick = onPlayTurn,
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.cd_play),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                Row {
                    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(28.dp)) {
                        Icon(
                            if (message.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            stringResource(R.string.history_tab_favorites),
                            modifier = Modifier.size(16.dp),
                            tint = if (message.isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Outlined.ContentCopy, stringResource(R.string.cd_copy),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCard(
    session: DialogueSession,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Mic, null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title.ifEmpty { stringResource(R.string.history_voice_session) },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = formatTime(session.updatedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    if (session.totalTurns > 0) {
                        Text(
                            text = " • ${session.totalTurns} turns",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (session.isRecorded || !session.audioFilePath.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Outlined.Audiotrack,
                            contentDescription = stringResource(R.string.history_audio_recording_available),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Icon(
                Icons.Filled.ChevronRight, null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Delete, stringResource(R.string.cd_delete),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(
    entry: TranslationEntry,
    onToggleFavorite: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isFavorite)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "[${entry.sourceLanguage.uppercase(Locale.ROOT)}] → [${entry.targetLanguage.uppercase(Locale.ROOT)}]",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = formatTime(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = entry.sourceText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = entry.translatedText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(32.dp)) {
                    Icon(
                        if (entry.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        stringResource(R.string.history_tab_favorites),
                        tint = if (entry.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.ContentCopy, stringResource(R.string.cd_copy),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Outlined.Delete, stringResource(R.string.cd_delete),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceMessageCard(
    message: DialogueMessage,
    onToggleFavorite: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (message.isFavorite)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "[${message.originalLanguage.uppercase(Locale.ROOT)}] → [${message.translatedLanguage.uppercase(Locale.ROOT)}]",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = formatTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.originalText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = message.translatedText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(28.dp)) {
                    Icon(
                        if (message.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        stringResource(R.string.history_tab_favorites),
                        modifier = Modifier.size(16.dp),
                        tint = if (message.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.ContentCopy, stringResource(R.string.cd_copy),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            modifier = Modifier.size(64.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
    }
}

private fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
