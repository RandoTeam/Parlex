package com.translive.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.ui.viewmodel.DialogueMessage
import com.translive.app.ui.viewmodel.DialoguePhase
import com.translive.app.ui.viewmodel.DialogueViewModel

@Composable
fun DialogueScreen(
    onNavigateToTranslate: () -> Unit,
    onNavigateToModels: () -> Unit,
    viewModel: DialogueViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll on new messages
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToTranslate,
                    icon = { Icon(Icons.Filled.Translate, "Translate") },
                    label = { Text("Текст") }
                )
                NavigationBarItem(
                    selected = true,
                    onClick = { },
                    icon = { Icon(Icons.Filled.Mic, "Dialogue") },
                    label = { Text("Диалог") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToModels,
                    icon = { Icon(Icons.Filled.Storage, "Models") },
                    label = { Text("Модели") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header with phase indicator
            DialogueHeader(phase = uiState.phase)

            // Check readiness
            val allReady = uiState.isTranslationModelReady && uiState.isSttReady

            if (!allReady) {
                // Setup required
                SetupPrompt(
                    hasTranslation = uiState.isTranslationModelReady,
                    hasStt = uiState.isSttReady,
                    hasTts = uiState.isTtsReady,
                    onNavigateToModels = onNavigateToModels,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // Message list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    if (uiState.messages.isEmpty() && !uiState.isConversationActive) {
                        item {
                            EmptyStateHint(modifier = Modifier.fillParentMaxSize())
                        }
                    }

                    items(uiState.messages) { message ->
                        DialogueBubble(
                            message = message,
                            onSpeak = { viewModel.speakMessage(message.translatedText) },
                            ttsReady = uiState.isTtsReady
                        )
                    }
                }

                // Error
                uiState.error?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Main action button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ConversationButton(
                        isActive = uiState.isConversationActive,
                        phase = uiState.phase,
                        onStart = { viewModel.startConversation() },
                        onStop = { viewModel.stopConversation() }
                    )
                }
            }
        }
    }
}

@Composable
private fun DialogueHeader(phase: DialoguePhase) {
    val phaseText = when (phase) {
        DialoguePhase.IDLE -> ""
        DialoguePhase.LISTENING -> "Слушаю..."
        DialoguePhase.RECOGNIZING -> "Распознаю..."
        DialoguePhase.TRANSLATING -> "Перевожу..."
        DialoguePhase.SPEAKING -> "Говорю..."
        DialoguePhase.ERROR -> "Ошибка"
    }

    val phaseColor = when (phase) {
        DialoguePhase.LISTENING -> MaterialTheme.colorScheme.primary
        DialoguePhase.SPEAKING -> MaterialTheme.colorScheme.tertiary
        DialoguePhase.ERROR -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

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
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Диалог",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.weight(1f))
            if (phaseText.isNotEmpty()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = phaseColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = phaseText,
                        style = MaterialTheme.typography.labelSmall,
                        color = phaseColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupPrompt(
    hasTranslation: Boolean,
    hasStt: Boolean,
    hasTts: Boolean,
    onNavigateToModels: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                modifier = Modifier.size(80.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.SettingsVoice, null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Настройка голоса",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Checklist
            SetupCheckRow("Модель перевода", hasTranslation)
            SetupCheckRow("Распознавание речи (STT)", hasStt)
            SetupCheckRow("Озвучивание (TTS)", hasTts)

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onNavigateToModels) {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Скачать модели")
            }
        }
    }
}

@Composable
private fun SetupCheckRow(label: String, isReady: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth(0.8f)
            .padding(vertical = 3.dp)
    ) {
        Icon(
            if (isReady) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            null,
            modifier = Modifier.size(18.dp),
            tint = if (isReady) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isReady) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyStateHint(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Outlined.RecordVoiceOver, null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Нажмите «Начать» и говорите",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Русский → English • English → Русский",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ConversationButton(
    isActive: Boolean,
    phase: DialoguePhase,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    // Pulsing animation when listening
    val scale by animateFloatAsState(
        targetValue = if (phase == DialoguePhase.LISTENING) 1.1f else 1.0f,
        animationSpec = if (phase == DialoguePhase.LISTENING) {
            infiniteRepeatable(
                animation = tween(800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        } else {
            tween(300)
        },
        label = "pulse"
    )

    val buttonColor = if (isActive) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.primary

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FloatingActionButton(
            onClick = { if (isActive) onStop() else onStart() },
            modifier = Modifier
                .size(80.dp)
                .scale(scale),
            containerColor = buttonColor,
            contentColor = Color.White,
            shape = CircleShape
        ) {
            Icon(
                if (isActive) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isActive) "Остановить" else "Начать",
                modifier = Modifier.size(36.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isActive) "Остановить" else "Начать разговор",
            style = MaterialTheme.typography.labelLarge,
            color = buttonColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun DialogueBubble(
    message: DialogueMessage,
    onSpeak: () -> Unit,
    ttsReady: Boolean
) {
    val langLabel = if (message.sourceLang == "ru") "🇷🇺" else "🇬🇧"
    val targetLabel = if (message.targetLang == "ru") "🇷🇺" else "🇬🇧"

    Column(modifier = Modifier.fillMaxWidth()) {
        // Source (original speech)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = langLabel,
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 4.dp, bottom = 8.dp)
            )
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    text = message.sourceText,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Translation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.Bottom
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    text = message.translatedText,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Text(
                text = targetLabel,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
            )
            if (ttsReady) {
                IconButton(
                    onClick = onSpeak,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.VolumeUp, "Озвучить",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
