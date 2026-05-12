package com.translive.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.engine.DownloadState
import com.translive.app.data.model.ModelFamily
import com.translive.app.data.model.SttModelInfo
import com.translive.app.ui.components.AppBottomNavigation
import com.translive.app.ui.components.BottomNavDestination
import com.translive.app.ui.viewmodel.FamilyUiState
import com.translive.app.ui.viewmodel.ModelItemState
import com.translive.app.ui.viewmodel.ModelManagerViewModel
import com.translive.app.ui.viewmodel.ModelStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    onNavigateToTranslate: () -> Unit,
    onNavigateToDialogue: () -> Unit,
    onNavigateToCamera: () -> Unit = {},
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: ModelManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // File picker for GGUF import
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importModelFromUri(it) }
    }

    // SAF picker for GGUF export
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let { viewModel.exportToUri(it) }
    }

    // Refresh on screen entry
    LaunchedEffect(Unit) { viewModel.refreshModels() }

    // Error/success snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            AppBottomNavigation(
                selected = BottomNavDestination.MODELS,
                onNavigateToTranslate = onNavigateToTranslate,
                onNavigateToDialogue = onNavigateToDialogue,
                onNavigateToCamera = onNavigateToCamera,
                onNavigateToHistory = onNavigateToHistory,
                onNavigateToModels = {},
                onNavigateToSettings = onNavigateToSettings
            )
        }
    ) { padding ->
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Header
            item(key = "header", contentType = "header") {
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
                    Column {
                        Text(
                            text = "Модели",
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${uiState.families.size} семейств • ${uiState.families.sumOf { it.variants.size }} квантизаций",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Storage info
            item(key = "storage", contentType = "storage") {
                StorageInfoCard(
                    totalDownloaded = uiState.totalDownloadedSize,
                    availableSpace = uiState.availableSpace,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Loading indicator
            if (uiState.isLoadingModel) {
                item(key = "loading", contentType = "loading") {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                    Text(
                        text = "Загрузка модели в память...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }

            // Family cards with expandable variants
            uiState.families.forEach { familyState ->
                item(
                    key = "family_${familyState.family.id}",
                    contentType = "family_header"
                ) {
                    FamilyHeader(
                        familyState = familyState,
                        onToggle = { viewModel.toggleFamily(familyState.family.id) },
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .animateItem()
                    )
                }

                if (familyState.isExpanded) {
                    items(
                        familyState.variants,
                        key = { it.variant.id },
                        contentType = { "model_card" }
                    ) { modelState ->
                        val variant = modelState.variant
                        val onDownload = remember(variant) { { viewModel.requestDownload(variant) } }
                        val onCancel = remember(variant) { { viewModel.cancelDownload(variant) } }
                        val onSelect = remember(variant) { { viewModel.selectModel(variant) } }
                        val onDelete = remember(variant) { { viewModel.deleteModel(variant) } }
                        val onExport = remember(variant) { {
                            viewModel.startExport(variant)
                            exportLauncher.launch(variant.filename)
                        } }
                        ModelCard(
                            state = modelState,
                            onDownload = onDownload,
                            onCancel = onCancel,
                            onSelect = onSelect,
                            onDelete = onDelete,
                            onExport = onExport,
                            isExporting = uiState.isExporting,
                            exportProgress = uiState.exportProgress,
                            modifier = Modifier
                                .padding(start = 32.dp, end = 16.dp, top = 2.dp, bottom = 2.dp)
                                .animateItem()
                        )
                    }
                }
            }

            // Import from file button
            item(key = "import_button", contentType = "import") {
                Spacer(modifier = Modifier.height(8.dp))

                if (uiState.isImporting) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text = "Импорт модели... ${(uiState.importProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { uiState.importProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    }
                } else {
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                    ) {
                        Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Установить из файла (.gguf)")
                    }
                }
            }

            // STT section
            item(key = "stt_header", contentType = "section_header") {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Распознавание (STT)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            item(key = "stt_card", contentType = "stt_card") {
                SttModelCard(
                    isDownloaded = uiState.sttDownloaded,
                    isDownloading = uiState.sttDownloading,
                    progress = uiState.sttProgress,
                    onDownload = { viewModel.downloadSttModels() },
                    onDelete = { viewModel.deleteSttModels() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
    }

    // Gemma license confirmation dialog
    uiState.pendingLicenseVariant?.let { variant ->
        val family = ModelFamily.familyOf(variant)
        AlertDialog(
            onDismissRequest = { viewModel.dismissLicenseDialog() },
            icon = { Icon(Icons.Outlined.Info, null) },
            title = { Text("Лицензия ${family?.license?.displayName ?: ""}") },
            text = {
                Text(
                    "Модель ${family?.name ?: variant.quantName} " +
                    "распространяется на условиях ${family?.license?.displayName ?: ""}. " +
                    "Скачивая, вы принимаете эти условия. Продолжить?"
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmLicenseAndDownload() }) {
                    Text("Принять и скачать")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLicenseDialog() }) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun FamilyHeader(
    familyState: FamilyUiState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val family = familyState.family
    Card(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (familyState.hasActiveVariant)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = family.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (family.isSpecialized) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text(
                                    text = "Перевод",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        if (familyState.hasActiveVariant) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                modifier = Modifier.padding(start = 6.dp)
                            ) {
                                Text(
                                    text = "✓ Активна",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${family.developer} • ${family.parameterSize} • ${family.languageCount} яз.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (familyState.downloadedCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            text = "${familyState.downloadedCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Icon(
                    if (familyState.isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (familyState.isExpanded) "Свернуть" else "Развернуть",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${family.description} • ${family.license.displayName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SttModelCard(
    isDownloaded: Boolean,
    isDownloading: Boolean,
    progress: Float,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isDownloaded)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.Mic, null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Silero VAD + Whisper Tiny",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (isDownloaded) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "✓ Готова",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = SttModelInfo.COMBINED_DESCRIPTION,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.FolderZip, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = SttModelInfo.COMBINED_SIZE_LABEL,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Outlined.Memory, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "~${SttModelInfo.WHISPER_RAM_MB} МБ RAM",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isDownloaded && !isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Скачать")
                    }
                }
            }

            if (isDownloaded && !isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Удалить")
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageInfoCard(
    totalDownloaded: Long,
    availableSpace: Long,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Outlined.Storage, "Storage",
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Скачано: ${formatSize(totalDownloaded)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Свободно: ${formatSize(availableSpace)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    state: ModelItemState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    isExporting: Boolean = false,
    exportProgress: Float = 0f,
    modifier: Modifier = Modifier
) {
    val variant = state.variant
    var showDeleteDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (state.status) {
                ModelStatus.ACTIVE -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top row: name + badges
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = variant.quantName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (variant.isRecommended) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "⭐ Рекомендуемая",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                if (state.status == ModelStatus.ACTIVE) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            text = "✓ Активна",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Description
            Text(
                text = variant.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Size info
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.FolderZip, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = remember(variant.sizeLabel) { variant.sizeLabel },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Outlined.Memory, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = "~${variant.ramEstimateMb} МБ RAM",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Download progress bar
            if (state.status == ModelStatus.DOWNLOADING) {
                val dl = state.downloadState
                if (dl is DownloadState.Downloading) {
                    Column {
                        LinearProgressIndicator(
                            progress = { dl.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${dl.progressPercent}% • ${formatSize(dl.bytesDownloaded)} / ${formatSize(dl.totalBytes)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (dl.speedBytesPerSec > 0) {
                                Text(
                                    text = "${formatSize(dl.speedBytesPerSec)}/с",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (state.status) {
                    ModelStatus.NOT_DOWNLOADED -> {
                        FilledTonalButton(onClick = onDownload) {
                            Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Скачать")
                        }
                    }
                    ModelStatus.DOWNLOADING -> {
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Отмена")
                        }
                    }
                    ModelStatus.DOWNLOADED -> {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Outlined.Delete, "Delete",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = onExport, enabled = !isExporting) {
                            Icon(
                                Icons.Outlined.Share, "Export",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onSelect) {
                            Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Выбрать")
                        }
                    }
                    ModelStatus.ACTIVE -> {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Outlined.Delete, "Delete",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            )
                        }
                        IconButton(onClick = onExport, enabled = !isExporting) {
                            Icon(
                                Icons.Outlined.Share, "Export",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(onClick = { }, enabled = false) {
                            Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Активна")
                        }
                    }
                    ModelStatus.LOADING -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Загрузка...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Удалить ${variant.quantName}?") },
            text = { Text("Файл ${variant.sizeLabel} будет удалён с устройства.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text("Удалить", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Отмена")
                }
            }
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f ГБ".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.0f МБ".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f КБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}
