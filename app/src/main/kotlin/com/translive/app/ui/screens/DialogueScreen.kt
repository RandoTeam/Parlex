package com.translive.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.data.model.Language
import com.translive.app.ui.components.AppBottomNavigation
import com.translive.app.ui.components.BottomNavDestination
import com.translive.app.ui.components.LanguagePickerSheet
import com.translive.app.ui.viewmodel.DialogueUiMessage
import com.translive.app.ui.viewmodel.DialoguePhase
import com.translive.app.ui.viewmodel.DialogueViewModel

@Composable
fun DialogueScreen(
    onNavigateToTranslate: () -> Unit,
    onNavigateToCamera: () -> Unit = {},
    onNavigateToHistory: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: DialogueViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current

    // Microphone permission
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setMicPermission(granted) }

    LaunchedEffect(Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.setMicPermission(true)
        else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Auto-scroll on new messages
    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        bottomBar = {
            AppBottomNavigation(
                selected = BottomNavDestination.DIALOGUE,
                onNavigateToTranslate = onNavigateToTranslate,
                onNavigateToDialogue = {},
                onNavigateToCamera = onNavigateToCamera,
                onNavigateToHistory = onNavigateToHistory,
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
            // Header with phase indicator
            DialogueHeader(phase = uiState.phase)

            // Language selector
            var showSourceLangPicker by remember { mutableStateOf(false) }
            var showTargetLangPicker by remember { mutableStateOf(false) }

            DialogueLanguageSelector(
                sourceLanguage = uiState.sourceLanguage,
                targetLanguage = uiState.targetLanguage,
                onSourceClick = { showSourceLangPicker = true },
                onTargetClick = { showTargetLangPicker = true },
                onSwap = { viewModel.swapLanguages() },
                enabled = !uiState.isConversationActive,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (showSourceLangPicker) {
                LanguagePickerSheet(
                    selectedLanguage = uiState.sourceLanguage,
                    onLanguageSelected = { viewModel.setSourceLanguage(it); showSourceLangPicker = false },
                    onDismiss = { showSourceLangPicker = false }
                )
            }
            if (showTargetLangPicker) {
                LanguagePickerSheet(
                    selectedLanguage = uiState.targetLanguage,
                    onLanguageSelected = { viewModel.setTargetLanguage(it); showTargetLangPicker = false },
                    onDismiss = { showTargetLangPicker = false }
                )
            }
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
                            onSpeakSource = { viewModel.speakMessage(message.sourceText, message.sourceLang) },
                            onSpeakTranslation = { viewModel.speakMessage(message.translatedText, message.targetLang) },
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
        }
    }
}

@Composable
private fun DialogueLanguageSelector(
    sourceLanguage: Language,
    targetLanguage: Language,
    onSourceClick: () -> Unit,
    onTargetClick: () -> Unit,
    onSwap: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        AssistChip(
            onClick = onSourceClick,
            label = { Text("${sourceLanguage.flag} ${sourceLanguage.nativeName}") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            enabled = enabled
        )

        IconButton(
            onClick = onSwap,
            enabled = enabled,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.1f else 0.05f))
        ) {
            Icon(
                Icons.Filled.SwapHoriz,
                contentDescription = "Swap languages",
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.outline
            )
        }

        AssistChip(
            onClick = onTargetClick,
            label = { Text("${targetLanguage.flag} ${targetLanguage.nativeName}") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            enabled = enabled
        )
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
    message: DialogueUiMessage,
    onSpeakSource: () -> Unit,
    onSpeakTranslation: () -> Unit,
    ttsReady: Boolean
) {
    val langLabel = Language.allLanguages.find { it.code == message.sourceLang }?.flag ?: "🌐"
    val targetLabel = Language.allLanguages.find { it.code == message.targetLang }?.flag ?: "🌐"

    Column(modifier = Modifier.fillMaxWidth()) {
        // Source (original speech)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom
        ) {
            if (ttsReady) {
                IconButton(
                    onClick = onSpeakSource,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.VolumeUp, "Озвучить",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                    )
                }
            }
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
                    onClick = onSpeakTranslation,
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
