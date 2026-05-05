package com.translive.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.data.model.Language
import com.translive.app.engine.TtsEngine
import com.translive.app.engine.TtsState
import com.translive.app.ui.components.LanguagePickerSheet
import com.translive.app.ui.theme.Teal
import com.translive.app.ui.viewmodel.TranslationViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslationScreen(
    onNavigateToDialogue: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: TranslationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val ttsEngine = viewModel.ttsEngine
    val ttsState by ttsEngine.state.collectAsState()

    var showSourceLangPicker by remember { mutableStateOf(false) }
    var showTargetLangPicker by remember { mutableStateOf(false) }

    // Auto-load model on first launch
    LaunchedEffect(Unit) { viewModel.loadModel() }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = true,
                    onClick = { },
                    icon = { Icon(Icons.Filled.Translate, "Translate") },
                    label = { Text("Текст") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToDialogue,
                    icon = { Icon(Icons.Filled.Mic, "Dialogue") },
                    label = { Text("Диалог") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToModels,
                    icon = { Icon(Icons.Filled.Storage, "Models") },
                    label = { Text("Модели") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToSettings,
                    icon = { Icon(Icons.Filled.Settings, "Settings") },
                    label = { Text("Настройки") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "Parlex",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Language selector row
            LanguageSelectorRow(
                sourceLanguage = uiState.sourceLanguage,
                targetLanguage = uiState.targetLanguage,
                onSourceClick = { showSourceLangPicker = true },
                onTargetClick = { showTargetLangPicker = true },
                onSwap = { viewModel.swapLanguages() },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Source input card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = uiState.sourceText,
                        onValueChange = { viewModel.setSourceText(it) },
                        placeholder = { Text("Введите текст для перевода...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedContainerColor = Color.Transparent
                        ),
                        textStyle = MaterialTheme.typography.bodyLarge
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            viewModel.setSourceText("")
                        }) {
                            Icon(Icons.Filled.Clear, "Clear", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = {
                            // TODO: Phase 2 — voice input
                        }) {
                            Icon(Icons.Filled.Mic, "Voice input", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Translate button
            Button(
                onClick = { viewModel.translate() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(52.dp),
                enabled = uiState.sourceText.isNotBlank() && uiState.isModelLoaded && !uiState.isTranslating,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                if (uiState.isTranslating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Перевод...")
                } else {
                    Icon(Icons.Filled.Translate, "Translate")
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Перевести")
                }
            }

            // Model loading indicator
            if (uiState.isModelLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Загрузка модели перевода...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }

            // Error display
            uiState.error?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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

            Spacer(modifier = Modifier.height(12.dp))

            // Translation result card
            AnimatedVisibility(
                visible = uiState.translatedText.isNotBlank(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = uiState.translatedText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            IconButton(onClick = {
                                clipboardManager.setText(AnnotatedString(uiState.translatedText))
                            }) {
                                Icon(
                                    Icons.Outlined.ContentCopy, "Copy",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(onClick = {
                                if (ttsState == TtsState.SPEAKING) {
                                    ttsEngine.stop()
                                } else {
                                    ttsEngine.speak(uiState.translatedText)
                                }
                            }, enabled = ttsEngine.isModelReady.collectAsState().value) {
                                Icon(
                                    if (ttsState == TtsState.SPEAKING) Icons.Filled.StopCircle
                                    else Icons.Filled.VolumeUp,
                                    "Speak",
                                    tint = if (ttsState == TtsState.SPEAKING)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Language pickers
    if (showSourceLangPicker) {
        LanguagePickerSheet(
            selectedLanguage = uiState.sourceLanguage,
            excludeLanguage = uiState.targetLanguage,
            onLanguageSelected = {
                viewModel.setSourceLanguage(it)
                showSourceLangPicker = false
            },
            onDismiss = { showSourceLangPicker = false }
        )
    }

    if (showTargetLangPicker) {
        LanguagePickerSheet(
            selectedLanguage = uiState.targetLanguage,
            excludeLanguage = uiState.sourceLanguage,
            onLanguageSelected = {
                viewModel.setTargetLanguage(it)
                showTargetLangPicker = false
            },
            onDismiss = { showTargetLangPicker = false }
        )
    }
}

@Composable
private fun LanguageSelectorRow(
    sourceLanguage: Language,
    targetLanguage: Language,
    onSourceClick: () -> Unit,
    onTargetClick: () -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Source language chip
        AssistChip(
            onClick = onSourceClick,
            label = { Text("${sourceLanguage.flag} ${sourceLanguage.nativeName}") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        )

        // Swap button
        IconButton(
            onClick = onSwap,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
        ) {
            Icon(
                Icons.Filled.SwapHoriz,
                contentDescription = "Swap languages",
                tint = MaterialTheme.colorScheme.primary
            )
        }

        // Target language chip
        AssistChip(
            onClick = onTargetClick,
            label = { Text("${targetLanguage.flag} ${targetLanguage.nativeName}") },
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        )
    }
}
