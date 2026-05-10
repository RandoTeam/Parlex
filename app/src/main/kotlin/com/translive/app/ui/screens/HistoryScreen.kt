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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.data.model.DialogueMessage
import com.translive.app.data.model.DialogueSession
import com.translive.app.data.model.TranslationEntry
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
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToTranslate,
                    icon = { Icon(Icons.Filled.Translate, "Translate") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToDialogue,
                    icon = { Icon(Icons.Filled.Mic, "Dialogue") }
                )
                NavigationBarItem(
                    selected = true,
                    onClick = { },
                    icon = { Icon(Icons.Filled.History, "History") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToModels,
                    icon = { Icon(Icons.Filled.Storage, "Models") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToSettings,
                    icon = { Icon(Icons.Filled.Settings, "Settings") }
                )
            }
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
                Text(
                    text = "История",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Tabs
            TabRow(
                selectedTabIndex = uiState.tab.ordinal,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = uiState.tab == HistoryTab.ALL,
                    onClick = { viewModel.setTab(HistoryTab.ALL) },
                    text = { Text("Все") },
                    icon = { Icon(Icons.Outlined.List, null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = uiState.tab == HistoryTab.FAVORITES,
                    onClick = { viewModel.setTab(HistoryTab.FAVORITES) },
                    text = { Text("Избранное") },
                    icon = { Icon(Icons.Outlined.Star, null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = uiState.tab == HistoryTab.VOICE,
                    onClick = { viewModel.setTab(HistoryTab.VOICE) },
                    text = { Text("Голос") },
                    icon = { Icon(Icons.Outlined.RecordVoiceOver, null, modifier = Modifier.size(18.dp)) }
                )
            }

            // Search bar
            if (uiState.tab != HistoryTab.VOICE || uiState.selectedSessionId != null) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    placeholder = { Text("Поиск по тексту...") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Filled.Close, "Очистить")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }

            // Language filter chips (not on voice sessions list)
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
                        label = { Text("Все") }
                    )
                    FilterChip(
                        selected = uiState.languageFilter == "ru-en",
                        onClick = { viewModel.setLanguageFilter("ru-en") },
                        label = { Text("🇷🇺 ↔ 🇬🇧") }
                    )
                    FilterChip(
                        selected = uiState.languageFilter == "ru-zh",
                        onClick = { viewModel.setLanguageFilter("ru-zh") },
                        label = { Text("🇷🇺 ↔ 🇨🇳") }
                    )
                    FilterChip(
                        selected = uiState.languageFilter == "en-zh",
                        onClick = { viewModel.setLanguageFilter("en-zh") },
                        label = { Text("🇬🇧 ↔ 🇨🇳") }
                    )
                }
            }

            // Content
            when (uiState.tab) {
                HistoryTab.ALL, HistoryTab.FAVORITES -> {
                    if (uiState.translations.isEmpty()) {
                        EmptyState(
                            if (uiState.tab == HistoryTab.ALL) "Нет переводов"
                            else "Нет избранных",
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                uiState.translations,
                                key = { it.id },
                                contentType = { "translation" }
                            ) { entry ->
                                TranslationHistoryCard(
                                    entry = entry,
                                    onToggleFavorite = { viewModel.toggleFavorite(entry) },
                                    onCopy = {
                                        clipboardManager.setText(AnnotatedString(entry.translatedText))
                                    },
                                    onDelete = { viewModel.deleteTranslation(entry) },
                                    modifier = Modifier.animateItem()
                                )
                            }
                        }
                    }
                }

                HistoryTab.VOICE -> {
                    if (uiState.selectedSessionId != null) {
                        // Session messages view
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { viewModel.selectSession(null) }) {
                                Icon(Icons.Filled.ArrowBack, "Назад")
                            }
                            Text(
                                "Сообщения сессии",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        if (uiState.selectedSessionMessages.isEmpty()) {
                            EmptyState("Нет сообщений", modifier = Modifier.weight(1f))
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(
                                    uiState.selectedSessionMessages,
                                    key = { it.id },
                                    contentType = { "voice_msg" }
                                ) { msg ->
                                    VoiceMessageCard(
                                        message = msg,
                                        onToggleFavorite = { viewModel.toggleVoiceFavorite(msg) },
                                        onCopy = {
                                            clipboardManager.setText(AnnotatedString("${msg.originalText}\n${msg.translatedText}"))
                                        },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }
                    } else {
                        // Sessions list
                        if (uiState.voiceSessions.isEmpty()) {
                            EmptyState("Нет голосовых сессий", modifier = Modifier.weight(1f))
                        } else {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(
                                    uiState.voiceSessions,
                                    key = { it.id },
                                    contentType = { "session" }
                                ) { session ->
                                    SessionCard(
                                        session = session,
                                        onClick = { viewModel.selectSession(session.id) },
                                        onDelete = { viewModel.deleteSession(session) },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Favorites from voice (shown below sessions on FAVORITES tab)
            if (uiState.tab == HistoryTab.FAVORITES && uiState.favoriteVoiceMessages.isNotEmpty()) {
                Text(
                    text = "🎤 Избранные голосовые",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(
                        uiState.favoriteVoiceMessages,
                        key = { "voice_${it.id}" },
                        contentType = { "voice_msg" }
                    ) { msg ->
                        VoiceMessageCard(
                            message = msg,
                            onToggleFavorite = { viewModel.toggleVoiceFavorite(msg) },
                            onCopy = {
                                clipboardManager.setText(AnnotatedString("${msg.originalText}\n${msg.translatedText}"))
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.Inbox, null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
private fun formatTime(timestamp: Long): String = dateFormat.format(Date(timestamp))

@Composable
private fun TranslationHistoryCard(
    entry: TranslationEntry,
    onToggleFavorite: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (entry.isFavorite)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Top row: language pair + time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${flagForLang(entry.sourceLanguage)} → ${flagForLang(entry.targetLanguage)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = formatTime(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Source text
            Text(
                text = entry.sourceText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Translation
            Text(
                text = entry.translatedText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = if (expanded) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis
            )

            // Action row
            AnimatedVisibility(visible = expanded) {
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
                            "Избранное",
                            tint = if (entry.isFavorite) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.ContentCopy, "Копировать",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Outlined.Delete, "Удалить",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.error
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
                    text = session.title.ifEmpty { "Голосовая сессия" },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            Icon(
                Icons.Filled.ChevronRight, null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(20.dp)
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Delete, "Удалить",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error
                )
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
    val srcFlag = flagForLang(message.originalLanguage)
    val tgtFlag = flagForLang(message.translatedLanguage)

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
                    text = "$srcFlag → $tgtFlag",
                    style = MaterialTheme.typography.labelSmall,
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
                        "Избранное",
                        modifier = Modifier.size(16.dp),
                        tint = if (message.isFavorite) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onCopy, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Outlined.ContentCopy, "Копировать",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private fun flagForLang(code: String): String = when {
    code.startsWith("ru") -> "🇷🇺"
    code.startsWith("en") -> "🇬🇧"
    code.startsWith("zh") -> "🇨🇳"
    code.startsWith("de") -> "🇩🇪"
    code.startsWith("fr") -> "🇫🇷"
    code.startsWith("es") -> "🇪🇸"
    code.startsWith("ja") -> "🇯🇵"
    code.startsWith("ko") -> "🇰🇷"
    else -> "🌐"
}
