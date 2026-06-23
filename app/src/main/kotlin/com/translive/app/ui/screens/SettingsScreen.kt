package com.translive.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.R
import com.translive.app.data.SettingsRepository
import com.translive.app.i18n.AppLocale
import com.translive.app.ui.components.AppBottomNavigation
import com.translive.app.ui.components.BottomNavDestination
import com.translive.app.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onNavigateToTranslate: () -> Unit,
    onNavigateToDialogue: () -> Unit,
    onNavigateToCamera: () -> Unit = {},
    onNavigateToHistory: () -> Unit,
    onNavigateToModels: () -> Unit,
    onNavigateToLogViewer: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val appLanguage by viewModel.appLanguage.collectAsState()
    val threads by viewModel.threads.collectAsState()
    val timeoutMinutes by viewModel.idleTimeout.collectAsState()
    val backend by viewModel.backend.collectAsState()
    val fileLoggingEnabled by viewModel.fileLoggingEnabled.collectAsState()

    Scaffold(
        bottomBar = {
            AppBottomNavigation(
                selected = BottomNavDestination.SETTINGS,
                onNavigateToTranslate = onNavigateToTranslate,
                onNavigateToDialogue = onNavigateToDialogue,
                onNavigateToCamera = onNavigateToCamera,
                onNavigateToHistory = onNavigateToHistory,
                onNavigateToModels = onNavigateToModels,
                onNavigateToSettings = {}
            )
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
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- Language section ---
            SectionHeader(icon = Icons.Outlined.Language, title = stringResource(R.string.settings_language_section))
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.settings_app_language), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_app_language_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                AppLanguageOption(
                    label = stringResource(R.string.settings_language_system),
                    selected = appLanguage == AppLocale.SYSTEM,
                    onClick = {
                        applyAppLanguage(context, viewModel, AppLocale.SYSTEM)
                    }
                )
                AppLanguageOption(
                    label = stringResource(R.string.settings_language_english),
                    selected = appLanguage == AppLocale.ENGLISH,
                    onClick = {
                        applyAppLanguage(context, viewModel, AppLocale.ENGLISH)
                    }
                )
                AppLanguageOption(
                    label = stringResource(R.string.settings_language_russian),
                    selected = appLanguage == AppLocale.RUSSIAN,
                    onClick = {
                        applyAppLanguage(context, viewModel, AppLocale.RUSSIAN)
                    }
                )
                AppLanguageOption(
                    label = stringResource(R.string.settings_language_zh_cn),
                    selected = appLanguage == AppLocale.CHINESE_SIMPLIFIED,
                    onClick = {
                        applyAppLanguage(context, viewModel, AppLocale.CHINESE_SIMPLIFIED)
                    }
                )
                AppLanguageOption(
                    label = stringResource(R.string.settings_language_zh_tw),
                    selected = appLanguage == AppLocale.CHINESE_TRADITIONAL,
                    onClick = {
                        applyAppLanguage(context, viewModel, AppLocale.CHINESE_TRADITIONAL)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Compute section ---
            SectionHeader(icon = Icons.Outlined.Memory, title = stringResource(R.string.settings_compute))

            // Threads
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.settings_cpu_threads), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_cpu_threads_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                val threadOptions = SettingsRepository.THREAD_OPTIONS
                val currentIndex = threadOptions.indexOf(threads).coerceAtLeast(0)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$threads",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = currentIndex.toFloat(),
                    onValueChange = { viewModel.setThreads(threadOptions[it.toInt()]) },
                    valueRange = 0f..(threadOptions.size - 1).toFloat(),
                    steps = threadOptions.size - 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${threadOptions.first()}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${threadOptions.last()}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Backend
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.settings_compute_backend), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_compute_backend_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                BackendOption(
                    label = "CPU",
                    description = stringResource(R.string.settings_backend_cpu_desc),
                    selected = backend == SettingsRepository.BACKEND_CPU,
                    enabled = true,
                    onClick = { viewModel.setBackend(SettingsRepository.BACKEND_CPU) }
                )
                BackendOption(
                    label = "GPU (Vulkan)",
                    description = stringResource(R.string.settings_backend_gpu_desc),
                    selected = backend == SettingsRepository.BACKEND_GPU,
                    enabled = true,
                    onClick = { viewModel.setBackend(SettingsRepository.BACKEND_GPU) }
                )
                BackendOption(
                    label = "NPU (NNAPI)",
                    description = stringResource(R.string.settings_backend_npu_desc),
                    selected = backend == SettingsRepository.BACKEND_NPU,
                    enabled = true,
                    onClick = { viewModel.setBackend(SettingsRepository.BACKEND_NPU) }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Memory section ---
            SectionHeader(icon = Icons.Outlined.Timer, title = stringResource(R.string.settings_memory))

            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(stringResource(R.string.settings_auto_unload), style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_auto_unload_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))

                val options = SettingsRepository.TIMEOUT_OPTIONS
                val currentIndex = options.indexOf(timeoutMinutes).coerceAtLeast(0)
                val currentLabel = if (timeoutMinutes == 0) stringResource(R.string.settings_disabled) else stringResource(R.string.minutes_short, timeoutMinutes)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = currentLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (timeoutMinutes == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.primary
                    )
                }

                Slider(
                    value = currentIndex.toFloat(),
                    onValueChange = { viewModel.setIdleTimeout(options[it.toInt()]) },
                    valueRange = 0f..(options.size - 1).toFloat(),
                    steps = options.size - 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.settings_off), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.minutes_short, options.last()), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // --- Debug section ---
            SectionHeader(icon = Icons.Outlined.BugReport, title = stringResource(R.string.settings_debug))
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_file_logging), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_file_logging_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = fileLoggingEnabled,
                        onCheckedChange = { viewModel.setFileLoggingEnabled(it) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToLogViewer() }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_view_logs),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Info ---
            SectionHeader(icon = Icons.Outlined.Info, title = stringResource(R.string.settings_about))
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                InfoRow(stringResource(R.string.settings_version), "1.4.0-beta.1")
                InfoRow(stringResource(R.string.settings_translation_model), "Hy-MT 1.5 1.8B")
                InfoRow("TTS", stringResource(R.string.settings_tts_engine_value))
                InfoRow("STT", "Whisper Tiny + Silero VAD")
                InfoRow(stringResource(R.string.settings_engine), "llama.cpp")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun applyAppLanguage(
    context: Context,
    viewModel: SettingsViewModel,
    languageCode: String
) {
    viewModel.setAppLanguage(languageCode)
    AppLocale.applyRuntimeLanguage(context, languageCode)
    context.findActivity()?.recreate()
}

@Composable
private fun SectionHeader(icon: ImageVector, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    ) {
        Icon(
            icon, null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            content = content
        )
    }
}

@Composable
private fun AppLanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

@Composable
private fun BackendOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled
        )
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                    alpha = if (enabled) 1f else 0.4f
                )
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}
