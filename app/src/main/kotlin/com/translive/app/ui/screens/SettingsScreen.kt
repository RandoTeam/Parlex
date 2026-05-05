package com.translive.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.data.SettingsRepository
import com.translive.app.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    onNavigateToTranslate: () -> Unit,
    onNavigateToDialogue: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToModels: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val threads by viewModel.threads.collectAsState()
    val timeoutMinutes by viewModel.idleTimeout.collectAsState()
    val backend by viewModel.backend.collectAsState()

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
                    selected = false,
                    onClick = onNavigateToDialogue,
                    icon = { Icon(Icons.Filled.Mic, "Dialogue") },
                    label = { Text("Диалог") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToHistory,
                    icon = { Icon(Icons.Filled.History, "History") },
                    label = { Text("История") }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToModels,
                    icon = { Icon(Icons.Filled.Storage, "Models") },
                    label = { Text("Модели") }
                )
                NavigationBarItem(
                    selected = true,
                    onClick = { },
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
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Настройки",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- Compute section ---
            SectionHeader(icon = Icons.Outlined.Memory, title = "Вычисления")

            // Threads
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Потоки CPU", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Больше потоков = быстрее, но больше нагрев",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsRepository.THREAD_OPTIONS.forEach { t ->
                        FilterChip(
                            selected = threads == t,
                            onClick = { viewModel.setThreads(t) },
                            label = { Text("$t") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Backend
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Бекенд вычислений", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "GPU и NPU требуют пересборки с Vulkan/NNAPI",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                BackendOption(
                    label = "CPU",
                    description = "Стабильно на всех устройствах",
                    selected = backend == SettingsRepository.BACKEND_CPU,
                    enabled = true,
                    onClick = { viewModel.setBackend(SettingsRepository.BACKEND_CPU) }
                )
                BackendOption(
                    label = "GPU (Vulkan)",
                    description = "Ускорение на поддерживаемых GPU • скоро",
                    selected = backend == SettingsRepository.BACKEND_GPU,
                    enabled = false,
                    onClick = { }
                )
                BackendOption(
                    label = "NPU (NNAPI)",
                    description = "Нейропроцессор • скоро",
                    selected = backend == SettingsRepository.BACKEND_NPU,
                    enabled = false,
                    onClick = { }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // --- Memory section ---
            SectionHeader(icon = Icons.Outlined.Timer, title = "Управление памятью")

            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("Авто-выгрузка модели", style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Через сколько минут простоя выгрузить модель из памяти",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SettingsRepository.TIMEOUT_OPTIONS.forEach { t ->
                        val label = if (t == 0) "Выкл" else "${t} мин"
                        FilterChip(
                            selected = timeoutMinutes == t,
                            onClick = { viewModel.setIdleTimeout(t) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                if (timeoutMinutes > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Модель выгрузится через $timeoutMinutes мин. после последнего перевода",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // --- Info ---
            SectionHeader(icon = Icons.Outlined.Info, title = "О приложении")
            SettingsCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                InfoRow("Версия", "1.0.0-beta")
                InfoRow("Модель перевода", "Hy-MT 1.5 1.8B")
                InfoRow("TTS", "Sherpa-ONNX Kokoro")
                InfoRow("STT", "Whisper Tiny + Silero VAD")
                InfoRow("Движок", "llama.cpp")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
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
