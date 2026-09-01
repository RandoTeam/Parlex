package com.translive.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.R
import com.translive.app.data.model.Language
import com.translive.app.ui.components.AppBottomNavigation
import com.translive.app.ui.components.BottomNavDestination
import com.translive.app.ui.components.LanguagePickerSheet
import com.translive.app.ui.viewmodel.DialoguePhase
import com.translive.app.ui.viewmodel.DialogueUiMessage
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
    val context = LocalContext.current

    // Microphone permission
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.setMicPermission(granted) }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
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

            // Zero-Emoji Material 3 Language selector
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

                    itemsIndexed(uiState.messages) { index, message ->
                        DialogueBubble(
                            message = message,
                            onSpeakSource = { viewModel.speakMessage(message.sourceText, message.sourceLang) },
                            onSpeakTranslation = { viewModel.speakMessage(message.translatedText, message.targetLang) },
                            onImproveWithLlm = { viewModel.improveMessageWithLlm(index) },
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

                // Apple Translate Single Hands-Free Control
                AppleConversationControl(
                    isActive = uiState.isConversationActive,
                    phase = uiState.phase,
                    sourceLanguage = uiState.sourceLanguage,
                    targetLanguage = uiState.targetLanguage,
                    isAutoSpeakEnabled = uiState.isAutoSpeakEnabled,
                    onToggleAutoSpeak = { viewModel.toggleAutoSpeak() },
                    onStart = { viewModel.startConversation() },
                    onStop = { viewModel.stopConversation() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun DialogueHeader(phase: DialoguePhase) {
    val phaseText = when (phase) {
        DialoguePhase.IDLE -> ""
        DialoguePhase.LISTENING -> stringResource(R.string.dialogue_phase_listening)
        DialoguePhase.RECOGNIZING -> stringResource(R.string.dialogue_phase_recognizing)
        DialoguePhase.TRANSLATING -> stringResource(R.string.dialogue_phase_translating)
        DialoguePhase.SPEAKING -> stringResource(R.string.dialogue_phase_speaking)
        DialoguePhase.ERROR -> stringResource(R.string.dialogue_phase_error)
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
                text = stringResource(R.string.nav_dialogue),
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
                text = stringResource(R.string.dialogue_voice_setup),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Checklist
            SetupCheckRow(stringResource(R.string.dialogue_setup_translation_model), hasTranslation)
            SetupCheckRow(stringResource(R.string.dialogue_setup_stt), hasStt)
            SetupCheckRow(stringResource(R.string.dialogue_setup_tts), hasTts)

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = onNavigateToModels) {
                Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.dialogue_download_models))
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
                text = stringResource(R.string.dialogue_empty_hint),
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
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = sourceLanguage.code.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = sourceLanguage.nativeName,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            },
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
                contentDescription = stringResource(R.string.cd_swap_languages),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.outline
            )
        }

        AssistChip(
            onClick = onTargetClick,
            label = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Text(
                            text = targetLanguage.code.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        text = targetLanguage.nativeName,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp),
            enabled = enabled
        )
    }
}

/**
 * Apple Translate Conversation Control:
 * Single floating pulsating Mic FAB, live sound wave indicator, and bilingual status capsule.
 */
@Composable
private fun AppleConversationControl(
    isActive: Boolean,
    phase: DialoguePhase,
    sourceLanguage: Language,
    targetLanguage: Language,
    isAutoSpeakEnabled: Boolean,
    onToggleAutoSpeak: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "halo")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isActive && phase == DialoguePhase.LISTENING) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val haloAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = if (isActive && phase == DialoguePhase.LISTENING) 0.0f else 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "haloAlpha"
    )

    val buttonColor = if (isActive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Bilingual Hands-Free Status Chip
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                if (isActive) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Слушаю: [${sourceLanguage.code.uppercase()}] ${sourceLanguage.displayName} или [${targetLanguage.code.uppercase()}] ${targetLanguage.displayName}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                } else {
                    Icon(
                        Icons.Outlined.HeadsetMic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${sourceLanguage.displayName} ↔ ${targetLanguage.displayName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Live Waveform Indicator when active
        if (isActive) {
            LiveAudioWaveformBar(
                phase = phase,
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(24.dp)
                    .padding(bottom = 8.dp)
            )
        }

        // Central Pulsing FAB + Balanced Mute Toggle Dock
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Symmetrical spacer so the central FAB remains perfectly centered
            Spacer(modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.width(20.dp))

            Box(
                modifier = Modifier.size(96.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isActive && phase == DialoguePhase.LISTENING) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .scale(pulseScale * 1.15f)
                            .clip(CircleShape)
                            .background(buttonColor.copy(alpha = haloAlpha))
                    )
                }

                FloatingActionButton(
                    onClick = { if (isActive) onStop() else onStart() },
                    modifier = Modifier
                        .size(76.dp)
                        .scale(if (isActive && phase == DialoguePhase.LISTENING) pulseScale else 1.0f),
                    containerColor = buttonColor,
                    contentColor = Color.White,
                    shape = CircleShape
                ) {
                    Icon(
                        if (isActive) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isActive) stringResource(R.string.dialogue_stop) else stringResource(R.string.dialogue_start_conversation),
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(20.dp))

            FilledTonalIconButton(
                onClick = onToggleAutoSpeak,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (isAutoSpeakEnabled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (isAutoSpeakEnabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            ) {
                Icon(
                    imageVector = if (isAutoSpeakEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                    contentDescription = if (isAutoSpeakEnabled) stringResource(R.string.cd_mute_auto_speak) else stringResource(R.string.cd_unmute_auto_speak),
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isActive) stringResource(R.string.dialogue_stop) else stringResource(R.string.dialogue_start_conversation),
            style = MaterialTheme.typography.labelLarge,
            color = buttonColor,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Animated sound bars reflecting listening/recognizing/speaking states.
 */
@Composable
private fun LiveAudioWaveformBar(
    phase: DialoguePhase,
    modifier: Modifier = Modifier
) {
    val barCount = 7
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until barCount) {
            val animDuration = 400 + (i * 90)
            val animatedHeight by infiniteTransition.animateFloat(
                initialValue = 4f,
                targetValue = if (phase == DialoguePhase.LISTENING || phase == DialoguePhase.SPEAKING) 20f else 4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(animDuration, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_$i"
            )

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(animatedHeight.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        when (phase) {
                            DialoguePhase.SPEAKING -> MaterialTheme.colorScheme.primary
                            DialoguePhase.LISTENING -> MaterialTheme.colorScheme.primary
                            DialoguePhase.RECOGNIZING -> MaterialTheme.colorScheme.secondary
                            else -> MaterialTheme.colorScheme.outlineVariant
                        }
                    )
            )
        }
    }
}

@Composable
private fun DialogueBubble(
    message: DialogueUiMessage,
    onSpeakSource: () -> Unit,
    onSpeakTranslation: () -> Unit,
    onImproveWithLlm: () -> Unit,
    ttsReady: Boolean
) {
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxWidth()) {
        // Source (original speech)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(end = 4.dp, bottom = 4.dp)
            ) {
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(message.sourceText)) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                if (ttsReady) {
                    IconButton(
                        onClick = onSpeakSource,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(R.string.cd_speak),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = message.sourceLang.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = message.sourceText,
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    message.sourceTransliteration?.let { trans ->
                        Text(
                            text = trans,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
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
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = message.targetLang.uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }

                        // Runtime Provenance Badge: FAST vs LLM
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (message.isLlmRefined) MaterialTheme.colorScheme.tertiaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                if (message.isLlmRefined) {
                                    Icon(
                                        Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(2.dp))
                                }
                                Text(
                                    text = if (message.isLlmRefined) "LLM" else "FAST",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (message.isLlmRefined) MaterialTheme.colorScheme.onTertiaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = message.translatedText,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    message.targetTransliteration?.let { trans ->
                        Text(
                            text = trans,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }

            // Action buttons on the right of translation bubble
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            ) {
                // Inline LLM Upgrade button
                if (!message.isLlmRefined) {
                    if (message.isImproving) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .padding(4.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else {
                        IconButton(
                            onClick = onImproveWithLlm,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = "Improve with LLM",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }

                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(message.translatedText)) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                if (ttsReady) {
                    IconButton(
                        onClick = onSpeakTranslation,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(R.string.cd_speak),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
