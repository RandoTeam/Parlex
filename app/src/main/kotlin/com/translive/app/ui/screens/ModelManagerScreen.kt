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
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.translive.app.R
import com.translive.app.data.SettingsRepository
import com.translive.app.data.model.Language
import com.translive.app.engine.DownloadState
import com.translive.app.data.model.ModelFamily
import com.translive.app.data.model.ModelPerformanceTier
import com.translive.app.data.model.ModelRuntime
import com.translive.app.data.model.ModelVariant
import com.translive.app.data.model.SttModelInfo
import com.translive.app.ui.components.AppBottomNavigation
import com.translive.app.ui.components.BottomNavDestination
import com.translive.app.ui.components.LanguagePickerSheet
import com.translive.app.ui.components.LanguagePacksHubSection
import com.translive.app.ui.viewmodel.CameraPackagePairUiState
import com.translive.app.ui.viewmodel.FamilyUiState
import com.translive.app.ui.viewmodel.CameraTranslationPackUiState
import com.translive.app.ui.viewmodel.ModelItemState
import com.translive.app.ui.viewmodel.ModelManagerViewModel
import com.translive.app.ui.viewmodel.ModelStatus
import com.translive.app.engine.FastTranslateEngine

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

    // File picker for GGUF / LiteRT-LM import
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importModelFromUri(it) }
    }

    val ocrImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let(viewModel::importOcrPackageFromUri) }

    // SAF picker for model export
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
                            text = stringResource(R.string.nav_models),
                            style = MaterialTheme.typography.headlineLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.models_summary, uiState.families.size, uiState.families.sumOf { it.variants.size }),
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

            // Travel Packs Hub
            if (uiState.travelPacks.isNotEmpty()) {
                item(key = "travel_packs", contentType = "travel_packs") {
                    LanguagePacksHubSection(
                        packs = uiState.travelPacks,
                        onDownloadPack = viewModel::downloadTravelPack,
                        onDeletePack = viewModel::deleteTravelPack,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
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
                        text = stringResource(R.string.model_loading_memory),
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
                        val onPause = remember(variant) { { viewModel.pauseDownload(variant) } }
                        val onResume = remember(variant) { { viewModel.resumeDownload(variant) } }
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
                            onPause = onPause,
                            onResume = onResume,
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

            if (uiState.externalModels.isNotEmpty()) {
                item(key = "external_models_header", contentType = "external_header") {
                    Text(
                        text = "Внешние модели",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 6.dp)
                    )
                }
                items(
                    uiState.externalModels,
                    key = { it.variant.id },
                    contentType = { "external_model_card" }
                ) { modelState ->
                    val variant = modelState.variant
                    ModelCard(
                        state = modelState,
                        onDownload = {},
                        onCancel = {},
                        onPause = {},
                        onResume = {},
                        onSelect = { viewModel.selectModel(variant) },
                        onDelete = { viewModel.deleteModel(variant) },
                        onExport = {
                            viewModel.startExport(variant)
                            exportLauncher.launch(variant.filename)
                        },
                        isExporting = uiState.isExporting,
                        exportProgress = uiState.exportProgress,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
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
                            text = stringResource(R.string.model_import_progress, (uiState.importProgress * 100).toInt()),
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
                        Text(stringResource(R.string.model_install_from_file))
                    }
                }
            }

            // STT section
            item(key = "stt_header", contentType = "section_header") {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.models_stt_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            item(key = "stt_card", contentType = "stt_card") {
                SttModelCard(
                    title = "Silero VAD + Whisper Tiny",
                    description = stringResource(R.string.stt_combined_description),
                    downloadSizeBytes = SttModelInfo.TOTAL_SIZE_BYTES,
                    ramMb = SttModelInfo.WHISPER_RAM_MB,
                    isDownloaded = uiState.sttDownloaded,
                    isDownloading = uiState.sttDownloading,
                    progress = uiState.sttProgress,
                    isSelected = uiState.selectedSpeechModel == SettingsRepository.SPEECH_MODEL_WHISPER_TINY,
                    onDownload = { viewModel.downloadSttModels() },
                    onSelect = { viewModel.selectSpeechModel(SettingsRepository.SPEECH_MODEL_WHISPER_TINY) },
                    onDelete = { viewModel.deleteSttModels() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            item(key = "qwen3_stt_card", contentType = "stt_card") {
                SttModelCard(
                    title = "Qwen3-ASR 0.6B INT8",
                    description = stringResource(R.string.stt_qwen3_description),
                    downloadSizeBytes = SttModelInfo.QWEN3_ARCHIVE_SIZE_BYTES,
                    ramMb = SttModelInfo.QWEN3_RAM_MB,
                    isDownloaded = uiState.qwen3Downloaded,
                    isDownloading = uiState.qwen3Downloading,
                    progress = uiState.qwen3Progress,
                    isSelected = uiState.selectedSpeechModel == SettingsRepository.SPEECH_MODEL_QWEN3_ASR_06B,
                    onDownload = { viewModel.downloadQwen3Model() },
                    onSelect = { viewModel.selectSpeechModel(SettingsRepository.SPEECH_MODEL_QWEN3_ASR_06B) },
                    onDelete = { viewModel.deleteQwen3Model() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            item(key = "camera_packs_header", contentType = "section_header") {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.models_camera_packages_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            item(key = "ocr_package", contentType = "ocr_package") {
                OcrPackageCard(
                    installed = uiState.ocrPackageInstalled,
                    busy = uiState.ocrPackageBusy,
                    onImport = { ocrImportLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            uiState.cameraPack?.let { pack ->
                item(key = "camera_packs_card", contentType = "camera_packs_card") {
                    CameraTranslationPackCard(
                        pack = pack,
                        onDownload = viewModel::downloadCameraTranslationPack,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

                if (uiState.cameraLanguagePacks.isNotEmpty()) {
                    item(key = "camera_language_packs", contentType = "camera_language_packs") {
                        CameraLanguagePacksGroup(
                            packs = uiState.cameraLanguagePacks,
                            pair = uiState.cameraPackagePair,
                            expanded = uiState.cameraLanguagePacksExpanded,
                            isBulkDownloading = uiState.isBulkDownloadingFastPackages,
                            bulkDownloadProgress = uiState.bulkDownloadProgress,
                            bulkDownloadedCount = uiState.bulkDownloadedCount,
                            bulkTotalCount = uiState.bulkTotalCount,
                            onToggle = viewModel::toggleCameraLanguagePacks,
                            onDownload = viewModel::downloadCameraLanguagePack,
                            onDelete = viewModel::deleteCameraLanguagePack,
                            onDownloadAll = viewModel::downloadAllFastLanguagePackages,
                            onPairSourceSelected = viewModel::selectCameraPackagePairSource,
                            onPairTargetSelected = viewModel::selectCameraPackagePairTarget,
                            onPairDownload = viewModel::downloadCameraPackagePair,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                }

                item(key = "dictionaries_header", contentType = "section_header") {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Офлайн-словари",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }

                item(key = "dictionaries_card", contentType = "dictionaries_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.MenuBook,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = "Встроенный словарь RU ↔ EN",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Статей в базе: ${uiState.dictionaryEntriesCount} • Мгновенный поиск",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = "Готов",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

    // License confirmation dialog
    uiState.pendingLicenseVariant?.let { variant ->
        val family = ModelFamily.familyOf(variant)
        AlertDialog(
            onDismissRequest = { viewModel.dismissLicenseDialog() },
            icon = { Icon(Icons.Outlined.Info, null) },
            title = { Text(stringResource(R.string.model_license_title, family?.license?.displayName ?: "")) },
            text = {
                Text(
                    stringResource(
                        R.string.model_license_message,
                        family?.name ?: variant.quantName,
                        family?.license?.displayName ?: ""
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmLicenseAndDownload() }) {
                    Text(stringResource(R.string.model_accept_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissLicenseDialog() }) {
                    Text(stringResource(R.string.notification_cancel))
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
                                    text = stringResource(R.string.model_category_translation),
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
                                    text = stringResource(R.string.model_active_checked),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (family.languageCount > 0) {
                            stringResource(R.string.model_family_details, family.developer, family.parameterSize, family.languageCount)
                        } else {
                            stringResource(R.string.model_family_details_without_languages, family.developer, family.parameterSize)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = familyBackendLabel(family),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
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
                familyState.downloadProgress?.let { progress ->
                    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 3.dp
                        )
                        Icon(
                            if (familyState.activeDownloadCount > 0) Icons.Filled.Downloading else Icons.Filled.Pause,
                            null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Icon(
                    if (familyState.isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (familyState.isExpanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${localizedFamilyDescription(family)} • ${family.license.displayName}",
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
    title: String,
    description: String,
    downloadSizeBytes: Long,
    ramMb: Int,
    isDownloaded: Boolean,
    isDownloading: Boolean,
    progress: Float,
    isSelected: Boolean,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (isDownloaded) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = stringResource(R.string.model_ready),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
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
                    text = formatSize(downloadSizeBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Outlined.Memory, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = stringResource(R.string.ram_mb, ramMb),
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
                        Text(stringResource(R.string.model_download))
                    }
                }
            }

            if (isDownloaded && !isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    if (!isSelected) {
                        FilledTonalButton(onClick = onSelect) {
                            Text(stringResource(R.string.model_select))
                        }
                    }
                    OutlinedButton(
                        onClick = onDelete,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Filled.Delete, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.model_delete))
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
                    text = stringResource(R.string.storage_downloaded, formatSize(totalDownloaded)),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = stringResource(R.string.storage_free, formatSize(availableSpace)),
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
    onPause: () -> Unit,
    onResume: () -> Unit,
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
                Text(
                    text = variant.backendLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = when (variant.performanceTier) {
                        ModelPerformanceTier.FAST_BUDGET -> MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f)
                        ModelPerformanceTier.BALANCED -> MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        ModelPerformanceTier.MAX_QUALITY -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                        ModelPerformanceTier.GPU_ACCELERATED -> MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    },
                    modifier = Modifier.padding(start = 6.dp)
                ) {
                    Text(
                        text = variant.performanceTier.badgeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (variant.performanceTier) {
                            ModelPerformanceTier.FAST_BUDGET -> MaterialTheme.colorScheme.secondary
                            ModelPerformanceTier.BALANCED -> MaterialTheme.colorScheme.primary
                            ModelPerformanceTier.MAX_QUALITY -> MaterialTheme.colorScheme.tertiary
                            ModelPerformanceTier.GPU_ACCELERATED -> MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (variant.isRecommended) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.model_recommended),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (state.status == ModelStatus.ACTIVE) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.padding(start = 6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.model_active_checked),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Description
            Text(
                text = localizedVariantDescription(variant),
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
                    text = formatSize(variant.sizeBytes),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(Icons.Outlined.Memory, null, modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = stringResource(R.string.ram_mb, variant.ramEstimateMb),
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
                                    text = stringResource(R.string.speed_per_second, formatSize(dl.speedBytesPerSec)),
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
                            Text(stringResource(R.string.model_download))
                        }
                    }
                    ModelStatus.DOWNLOADING -> {
                        IconButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, "Pause", tint = MaterialTheme.colorScheme.primary)
                        }
                        OutlinedButton(onClick = onCancel) {
                            Icon(Icons.Filled.Close, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.notification_cancel))
                        }
                    }
                    ModelStatus.PAUSED -> {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.cd_delete), tint = MaterialTheme.colorScheme.error)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onResume) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Продолжить")
                        }
                    }
                    ModelStatus.DOWNLOADED -> {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Outlined.Delete, stringResource(R.string.cd_delete),
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(onClick = onExport, enabled = !isExporting) {
                            Icon(
                                Icons.Outlined.Share, stringResource(R.string.cd_export),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onSelect) {
                            Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.model_select))
                        }
                    }
                    ModelStatus.ACTIVE -> {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                Icons.Outlined.Delete, stringResource(R.string.cd_delete),
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                            )
                        }
                        IconButton(onClick = onExport, enabled = !isExporting) {
                            Icon(
                                Icons.Outlined.Share, stringResource(R.string.cd_export),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(onClick = { }, enabled = false) {
                            Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.model_active))
                        }
                    }
                    ModelStatus.LOADING -> {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.model_loading), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.model_delete_title, variant.quantName)) },
            text = { Text(stringResource(R.string.model_delete_message, formatSize(variant.sizeBytes))) },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteDialog = false
                }) {
                    Text(stringResource(R.string.model_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.notification_cancel))
                }
            }
        )
    }
}

@Composable
private fun localizedFamilyDescription(family: ModelFamily): String {
    val resourceId = when (family.id) {
        "hy_mt" -> R.string.model_family_hy_mt_desc
        "hy_mt2_1_8b" -> R.string.model_family_hy_mt2_1_8b_desc
        "hy_mt2_7b" -> R.string.model_family_hy_mt2_7b_desc
        "translate_gemma" -> R.string.model_family_translate_gemma_desc
        "translate_gemma_litert_beta" -> R.string.model_family_translate_gemma_litert_beta_desc
        else -> null
    }
    return resourceId?.let { stringResource(it) } ?: family.description
}

@Composable
private fun OcrPackageCard(
    installed: Boolean,
    busy: Boolean,
    onImport: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.DocumentScanner, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("PP-OCRv6 tiny (MNN)", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                Text(
                    if (installed) "Установлен и проверен; быстрый OCR OpenCL"
                    else "Detector + recognizer для камеры, фото и экрана",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                installed -> Icon(Icons.Filled.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary)
                else -> TextButton(onClick = onImport) { Text("Импорт ZIP") }
            }
        }
    }
}

@Composable
private fun CameraTranslationPackCard(
    pack: CameraTranslationPackUiState,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (pack.isReady)
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
                    Icons.Filled.CameraAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.models_camera_translation),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (pack.isReady) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = stringResource(R.string.model_ready),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(
                    R.string.models_camera_current_pair,
                    pack.sourceLanguage.displayName,
                    pack.targetLanguage.displayName
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (pack.isAutoSource) {
                    stringResource(R.string.models_camera_auto_source_note)
                } else {
                    stringResource(R.string.models_camera_package_note)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!pack.supported) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.models_camera_unsupported_pair),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(
                    R.string.models_camera_package_progress,
                    pack.downloadedPackageCount,
                    pack.requiredPackageCount
                ),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (pack.isDownloading) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.models_camera_downloading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (!pack.isReady) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilledTonalButton(onClick = onDownload) {
                        Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.models_camera_download_packages))
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraLanguagePacksGroup(
    packs: List<com.translive.app.ui.viewmodel.CameraLanguagePackUiState>,
    pair: CameraPackagePairUiState?,
    expanded: Boolean,
    isBulkDownloading: Boolean,
    bulkDownloadProgress: Float,
    bulkDownloadedCount: Int,
    bulkTotalCount: Int,
    onToggle: () -> Unit,
    onDownload: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDownloadAll: () -> Unit,
    onPairSourceSelected: (Language) -> Unit,
    onPairTargetSelected: (Language) -> Unit,
    onPairDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fastPacks = packs.filter { it.fastSupported }
    val readyCount = fastPacks.count { it.isDownloaded }
    val totalCount = fastPacks.size
    val installedBytes = readyCount * FastTranslateEngine.PACKAGE_SIZE_BYTES
    val totalBytes = totalCount * FastTranslateEngine.PACKAGE_SIZE_BYTES
    val missingCount = totalCount - readyCount
    val missingBytes = missingCount * FastTranslateEngine.PACKAGE_SIZE_BYTES

    val supportedLanguages = remember(packs) {
        packs.flatMap { it.languages }.distinct().ifEmpty { Language.allLanguages }
    }
    var selectingSource by remember { mutableStateOf(false) }
    var selectingTarget by remember { mutableStateOf(false) }

    Card(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Filled.Translate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.models_camera_all_language_packages),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "Установлено $readyCount / $totalCount (${formatSize(installedBytes)} / ${formatSize(totalBytes)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (readyCount == totalCount && totalCount > 0)
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Text(
                        text = "$readyCount / $totalCount",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (readyCount == totalCount && totalCount > 0)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) stringResource(R.string.cd_collapse) else stringResource(R.string.cd_expand),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(Modifier.height(12.dp))

                // Source origin metadata badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${FastTranslateEngine.DOWNLOAD_SOURCE_NAME} • Офлайн-модели NMT",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.models_camera_all_packages_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Bulk Download Action Section
                Spacer(Modifier.height(12.dp))
                if (isBulkDownloading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Загрузка всех пакетов ($bulkDownloadedCount / $bulkTotalCount)…",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "${(bulkDownloadProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { bulkDownloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                        )
                    }
                } else if (missingCount > 0) {
                    FilledTonalButton(
                        onClick = onDownloadAll,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Скачать все пакеты (~${formatSize(missingBytes)})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Все языковые пакеты установлены",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Pair selector
                pair?.let { selectedPair ->
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.models_camera_pair_download_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AssistChip(
                            onClick = { selectingSource = true },
                            label = { Text(selectedPair.sourceLanguage.displayName, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "→",
                            modifier = Modifier.padding(horizontal = 6.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        AssistChip(
                            onClick = { selectingTarget = true },
                            label = { Text(selectedPair.targetLanguage.displayName, maxLines = 1) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.models_camera_package_progress,
                            selectedPair.downloadedPackageCount,
                            selectedPair.requiredPackageCount
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (!selectedPair.fastSupported) {
                            Text(
                                text = stringResource(R.string.models_camera_unsupported_pair),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                        } else if (selectedPair.isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        } else if (selectedPair.isReady) {
                            Text(
                                text = stringResource(R.string.model_ready),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(vertical = 10.dp)
                            )
                        } else {
                            FilledTonalButton(onClick = onPairDownload) {
                                Icon(Icons.Filled.Download, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.models_camera_download_pair))
                            }
                        }
                    }
                }

                // Individual Package Rows
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                Spacer(Modifier.height(8.dp))

                packs.forEach { pack ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Zero-Emoji Material 3 ISO Code Tag
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (pack.isDownloaded)
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.width(48.dp)
                        ) {
                            Text(
                                text = pack.modelLanguageCode.replace("llm:", "").uppercase(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (pack.isDownloaded)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                maxLines = 1
                            )
                        }

                        Spacer(Modifier.width(10.dp))

                        // Names, Size, and Source
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pack.languages.joinToString(", ") { it.displayName },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (pack.fastSupported)
                                    "~${formatSize(pack.sizeBytes)} • ${pack.downloadSource}"
                                else
                                    "Локальная LLM модель",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Actions
                        when {
                            !pack.fastSupported -> {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text(
                                        text = "LLM Fallback",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            pack.isDownloading -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp
                                )
                            }
                            pack.isDeleting -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            pack.isDownloaded -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.model_ready),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick = { onDelete(pack.modelLanguageCode) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = stringResource(R.string.model_delete),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                            else -> {
                                IconButton(
                                    onClick = { onDownload(pack.modelLanguageCode) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Download,
                                        contentDescription = stringResource(R.string.model_download),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectingSource && pair != null) {
        LanguagePickerSheet(
            selectedLanguage = pair.sourceLanguage,
            excludeLanguage = pair.targetLanguage,
            availableLanguages = supportedLanguages,
            onLanguageSelected = {
                onPairSourceSelected(it)
                selectingSource = false
            },
            onDismiss = { selectingSource = false }
        )
    }
    if (selectingTarget && pair != null) {
        LanguagePickerSheet(
            selectedLanguage = pair.targetLanguage,
            excludeLanguage = pair.sourceLanguage,
            availableLanguages = supportedLanguages,
            onLanguageSelected = {
                onPairTargetSelected(it)
                selectingTarget = false
            },
            onDismiss = { selectingTarget = false }
        )
    }
}

private fun familyBackendLabel(family: ModelFamily): String {
    val variants = family.variants
    if (variants.all { it.runtime == ModelRuntime.GGUF }) {
        return "CPU / GPU (OpenCL)"
    }
    val cpu = variants.any { it.supportsCpu }
    val gpu = variants.any { it.supportsGpu }
    return when {
        cpu && gpu && variants.all { it.supportsCpu && it.supportsGpu } -> "CPU / GPU"
        cpu && gpu -> "CPU / GPU — зависит от варианта"
        gpu -> "GPU only"
        else -> "CPU only"
    }
}

@Composable
private fun localizedVariantDescription(variant: ModelVariant): String {
    val resourceId = when (variant.id) {
        "hy_mt:1_25bit" -> R.string.model_variant_hy_mt_1_25bit_desc
        "hy_mt:2bit" -> R.string.model_variant_hy_mt_2bit_desc
        "hy_mt:q4_k_m" -> R.string.model_variant_hy_mt_q4_k_m_desc
        "hy_mt:q6_k" -> R.string.model_variant_hy_mt_q6_k_desc
        "hy_mt:q8_0" -> R.string.model_variant_hy_mt_q8_0_desc
        "hy_mt2_1_8b:1_25bit" -> R.string.model_variant_hy_mt2_1_8b_1_25bit_desc
        "hy_mt2_1_8b:2bit" -> R.string.model_variant_hy_mt2_1_8b_2bit_desc
        "hy_mt2_1_8b:q4_k_m" -> R.string.model_variant_hy_mt2_1_8b_q4_k_m_desc
        "hy_mt2_1_8b:q6_k" -> R.string.model_variant_hy_mt2_1_8b_q6_k_desc
        "hy_mt2_1_8b:q8_0" -> R.string.model_variant_hy_mt2_1_8b_q8_0_desc
        "hy_mt2_7b:q4_k_m" -> R.string.model_variant_hy_mt2_7b_q4_k_m_desc
        "hy_mt2_7b:q6_k" -> R.string.model_variant_hy_mt2_7b_q6_k_desc
        "hy_mt2_7b:q8_0" -> R.string.model_variant_hy_mt2_7b_q8_0_desc
        "translate_gemma:q2_k" -> R.string.model_variant_translate_gemma_q2_k_desc
        "translate_gemma:q3_k_s" -> R.string.model_variant_translate_gemma_q3_k_s_desc
        "translate_gemma:q3_k_m" -> R.string.model_variant_translate_gemma_q3_k_m_desc
        "translate_gemma:q3_k_l" -> R.string.model_variant_translate_gemma_q3_k_l_desc
        "translate_gemma:iq4_xs" -> R.string.model_variant_translate_gemma_iq4_xs_desc
        "translate_gemma:q4_k_s" -> R.string.model_variant_translate_gemma_q4_k_s_desc
        "translate_gemma:q4_k_m" -> R.string.model_variant_translate_gemma_q4_k_m_desc
        "translate_gemma:q5_k_s" -> R.string.model_variant_translate_gemma_q5_k_s_desc
        "translate_gemma:q5_k_m" -> R.string.model_variant_translate_gemma_q5_k_m_desc
        "translate_gemma:q6_k" -> R.string.model_variant_translate_gemma_q6_k_desc
        "translate_gemma:q8_0" -> R.string.model_variant_translate_gemma_q8_0_desc
        "translate_gemma:f16" -> R.string.model_variant_translate_gemma_f16_desc
        "translate_gemma_litert_beta:int4" -> R.string.model_variant_translate_gemma_litert_beta_int4_desc
        "translate_gemma_litert_beta:dynamic_int8" -> R.string.model_variant_translate_gemma_litert_beta_dynamic_int8_desc
        else -> null
    }
    return resourceId?.let { stringResource(it) } ?: variant.description
}

@Composable
private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> stringResource(R.string.size_gb, bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> stringResource(R.string.size_mb, bytes / 1_048_576.0)
    bytes >= 1024L -> stringResource(R.string.size_kb, bytes / 1024.0)
    else -> stringResource(R.string.size_bytes, bytes)
}
