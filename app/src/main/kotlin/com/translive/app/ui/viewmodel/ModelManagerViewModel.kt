package com.translive.app.ui.viewmodel

import android.net.Uri

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.data.ModelRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.model.ModelCatalog
import com.translive.app.data.model.ModelFamily
import com.translive.app.data.model.ModelRuntime
import com.translive.app.data.model.ModelVariant
import com.translive.app.data.model.SttModelInfo
import com.translive.app.engine.DownloadState
import com.translive.app.engine.LiteRtTranslationEngine
import com.translive.app.engine.ModelDownloadManager
import com.translive.app.engine.SpeechEngine
import com.translive.app.engine.TranslationEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import javax.inject.Inject
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

enum class ModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    DOWNLOADED,
    ACTIVE,
    LOADING
}

data class ModelItemState(
    val variant: ModelVariant,
    val status: ModelStatus,
    val downloadState: DownloadState = DownloadState.Idle
)

data class FamilyUiState(
    val family: ModelFamily,
    val isExpanded: Boolean = false,
    val variants: List<ModelItemState> = emptyList(),
    /** Number of downloaded variants in this family */
    val downloadedCount: Int = 0,
    /** True if the active model belongs to this family */
    val hasActiveVariant: Boolean = false
)

data class ModelManagerUiState(
    val families: List<FamilyUiState> = emptyList(),
    val models: List<ModelItemState> = emptyList(),
    val totalDownloadedSize: Long = 0L,
    val availableSpace: Long = 0L,
    val isLoadingModel: Boolean = false,
    val isImporting: Boolean = false,
    val importProgress: Float = 0f,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val sttDownloaded: Boolean = false,
    val sttDownloading: Boolean = false,
    val sttProgress: Float = 0f,
    val error: String? = null,
    val successMessage: String? = null,
    /** Variant pending license confirmation before download */
    val pendingLicenseVariant: ModelVariant? = null
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    private val repo: ModelRepository,
    private val downloadManager: ModelDownloadManager,
    private val engine: TranslationEngine,
    private val liteRtEngine: LiteRtTranslationEngine,
    private val speechEngine: SpeechEngine,
    private val settings: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "ModelManagerVM"
    }

    private val _uiState = MutableStateFlow(ModelManagerUiState())
    val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

    /** Variant awaiting SAF picker result for export */
    var pendingExportVariant: ModelVariant? = null
        private set

    init {
        refreshModels()

        // Observe persistent download states from the singleton manager
        viewModelScope.launch {
            downloadManager.activeDownloads.collect { downloads ->
                updateDownloadStates(downloads)
            }
        }
    }

    private fun updateDownloadStates(downloads: Map<String, DownloadState>) {
        val activeId = repo.getActiveModelId()
        val expandedIds = _uiState.value.families.filter { it.isExpanded }.map { it.family.id }.toSet()

        val families = ModelCatalog.ALL_FAMILIES.map { family ->
            val variantStates = family.variants.map { variant ->
                val isDownloaded = repo.isDownloaded(variant)
                val isActive = variant.id == activeId && isDownloaded
                val downloadState = downloads[variant.id]
                val status = when {
                    isActive -> ModelStatus.ACTIVE
                    downloadState is DownloadState.Downloading -> ModelStatus.DOWNLOADING
                    isDownloaded -> ModelStatus.DOWNLOADED
                    else -> ModelStatus.NOT_DOWNLOADED
                }
                ModelItemState(variant, status, downloadState ?: DownloadState.Idle)
            }
            FamilyUiState(
                family = family,
                isExpanded = family.id in expandedIds,
                variants = variantStates,
                downloadedCount = variantStates.count { it.status == ModelStatus.DOWNLOADED || it.status == ModelStatus.ACTIVE },
                hasActiveVariant = variantStates.any { it.status == ModelStatus.ACTIVE }
            )
        }

        // Flat list for backward compat
        val allModels = families.flatMap { it.variants }

        val sttVadState = downloads["stt-vad"]
        val sttWhisperState = downloads["stt-whisper"]

        _uiState.update { old ->
            old.copy(
                families = families,
                models = allModels,
                totalDownloadedSize = repo.getTotalDownloadedSize(),
                availableSpace = repo.getAvailableSpace(),
                sttDownloaded = speechEngine.areModelsDownloaded(),
                sttDownloading = sttVadState is DownloadState.Downloading ||
                        sttWhisperState is DownloadState.Downloading,
                sttProgress = when {
                    sttWhisperState is DownloadState.Downloading ->
                        0.05f + sttWhisperState.progress * 0.9f
                    sttVadState is DownloadState.Downloading ->
                        sttVadState.progress * 0.05f
                    else -> old.sttProgress
                }
            )
        }
    }

    fun refreshModels() {
        updateDownloadStates(downloadManager.activeDownloads.value)
    }

    fun toggleFamily(familyId: String) {
        _uiState.update { old ->
            old.copy(
                families = old.families.map { f ->
                    if (f.family.id == familyId) f.copy(isExpanded = !f.isExpanded) else f
                }
            )
        }
    }

    /** Request download — shows license dialog if needed */
    fun requestDownload(variant: ModelVariant) {
        val family = ModelFamily.familyOf(variant)
        if (family != null && family.requiresLicenseConfirmation) {
            _uiState.update { it.copy(pendingLicenseVariant = variant) }
        } else {
            downloadModel(variant)
        }
    }

    fun confirmLicenseAndDownload() {
        val variant = _uiState.value.pendingLicenseVariant ?: return
        _uiState.update { it.copy(pendingLicenseVariant = null) }
        downloadModel(variant)
    }

    fun dismissLicenseDialog() {
        _uiState.update { it.copy(pendingLicenseVariant = null) }
    }

    fun downloadModel(variant: ModelVariant) {
        if (repo.getAvailableSpace() < variant.sizeBytes * 1.1) {
            _uiState.update { it.copy(error = "Недостаточно места: нужно ${variant.sizeLabel}") }
            return
        }

        val destFile = repo.getDownloadFile(variant)

        downloadManager.startDownload(variant, destFile) { state ->
            when (state) {
                is DownloadState.Completed -> {
                    if (repo.getActiveModelId() == null) {
                        selectModel(variant)
                    } else {
                        refreshModels()
                    }
                }
                is DownloadState.Failed -> {
                    _uiState.update { it.copy(error = "Ошибка: ${state.error}") }
                }
                else -> {}
            }
        }
    }

    fun cancelDownload(variant: ModelVariant) {
        downloadManager.cancelDownload(variant.id)
    }

    fun selectModel(variant: ModelVariant) {
        if (!repo.isDownloaded(variant)) return

        _uiState.update { it.copy(isLoadingModel = true, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.unloadModel()
                liteRtEngine.unloadModel()
                repo.setActiveModelId(variant.id)
                val path = repo.getModelPath(variant) ?: return@launch

                val threads = settings.threads
                val loaded = if (variant.runtime == ModelRuntime.LITERT_LM) {
                    liteRtEngine.loadModel(path, settings.backend, threads)
                } else {
                    engine.loadModel(path, threads)
                }

                if (!loaded) {
                    _uiState.update { it.copy(error = "Не удалось загрузить модель ${variant.quantName}") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = "Ошибка: ${e.message}") }
            } finally {
                _uiState.update { it.copy(isLoadingModel = false) }
                refreshModels()
            }
        }
    }

    fun deleteModel(variant: ModelVariant) {
        if (repo.getActiveModelId() == variant.id) {
            engine.unloadModel()
            liteRtEngine.unloadModel()
        }
        repo.deleteModel(variant)
        refreshModels()
    }



    fun deleteSttModels() {
        speechEngine.release()
        val sttDir = File(speechEngine.vadFile.parent ?: return)
        if (sttDir.exists()) sttDir.deleteRecursively()
        refreshModels()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null, successMessage = null) }
    }

    /** Called by UI when user presses Export on a model card */
    fun startExport(variant: ModelVariant) {
        pendingExportVariant = variant
    }

    /** Called by UI after SAF CreateDocument picker returns a URI */
    fun exportToUri(uri: Uri) {
        val variant = pendingExportVariant ?: return
        pendingExportVariant = null

        if (_uiState.value.isExporting) return
        _uiState.update { it.copy(isExporting = true, exportProgress = 0f) }

        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.exportModel(variant, uri) { progress ->
                _uiState.update { it.copy(exportProgress = progress) }
            }

            result.fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            exportProgress = 1f,
                            successMessage = "Модель \"${variant.quantName}\" экспортирована"
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            error = "Экспорт: ${error.message}"
                        )
                    }
                }
            )
        }
    }

    fun importModelFromUri(uri: Uri) {
        if (_uiState.value.isImporting) return
        _uiState.update { it.copy(isImporting = true, importProgress = 0f, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val result = repo.importModelFromUri(uri) { progress ->
                _uiState.update { it.copy(importProgress = progress) }
            }

            result.fold(
                onSuccess = { filename ->
                    // Auto-activate if no model is currently active
                    val shouldActivate = repo.getActiveModelId() == null
                    if (shouldActivate) {
                        repo.setActiveByFilename(filename)
                        val path = repo.getActiveModelPath()
                        if (path != null) {
                            val threads = settings.threads
                            if (repo.getActiveRuntime() == ModelRuntime.LITERT_LM) {
                                engine.unloadModel()
                                liteRtEngine.loadModel(path, settings.backend, threads)
                            } else {
                                liteRtEngine.unloadModel()
                                engine.loadModel(path, threads)
                            }
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importProgress = 1f,
                            successMessage = "Модель \"$filename\" установлена"
                        )
                    }
                    refreshModels()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            error = error.message ?: "Ошибка импорта"
                        )
                    }
                }
            )
        }
    }



    fun downloadSttModels() {
        if (_uiState.value.sttDownloading) return
        _uiState.update { it.copy(sttDownloading = true, sttProgress = 0f) }

        val sttDir = File(speechEngine.vadFile.parent ?: return)
        sttDir.mkdirs()

        // Step 1: VAD
        val vadVariant = ModelVariant(
            id = "stt-vad",
            quantName = SttModelInfo.VAD_DISPLAY_NAME,
            displayName = SttModelInfo.VAD_DISPLAY_NAME,
            description = "VAD",
            sizeBytes = SttModelInfo.VAD_SIZE_BYTES,
            ramEstimateMb = 50,
            downloadUrl = SttModelInfo.VAD_DOWNLOAD_URL,
            filename = SttModelInfo.VAD_FILENAME
        )

        downloadManager.startDownload(vadVariant, speechEngine.vadFile) { vadState ->
            when (vadState) {
                is DownloadState.Completed -> {
                    // VAD done — now download Whisper
                    downloadWhisper(sttDir)
                }
                is DownloadState.Failed -> {
                    _uiState.update {
                        it.copy(sttDownloading = false, error = "VAD: ${vadState.error}")
                    }
                }
                is DownloadState.Cancelled -> {
                    _uiState.update { it.copy(sttDownloading = false) }
                }
                else -> {}
            }
        }
    }

    private fun downloadWhisper(sttDir: File) {
        val whisperArchive = File(sttDir, SttModelInfo.WHISPER_ARCHIVE)
        val whisperVariant = ModelVariant(
            id = "stt-whisper",
            quantName = SttModelInfo.WHISPER_DISPLAY_NAME,
            displayName = SttModelInfo.WHISPER_DISPLAY_NAME,
            description = SttModelInfo.WHISPER_DESCRIPTION,
            sizeBytes = SttModelInfo.WHISPER_SIZE_BYTES,
            ramEstimateMb = SttModelInfo.WHISPER_RAM_MB,
            downloadUrl = "${SttModelInfo.WHISPER_BASE_URL}/${SttModelInfo.WHISPER_ARCHIVE}",
            filename = SttModelInfo.WHISPER_ARCHIVE
        )

        downloadManager.startDownload(whisperVariant, whisperArchive) { state ->
            when (state) {
                is DownloadState.Completed -> {
                    try {
                        _uiState.update { it.copy(sttProgress = 0.95f) }
                        withContext(Dispatchers.IO) {
                            extractTarBz2(whisperArchive, sttDir)
                            whisperArchive.delete()
                        }
                        _uiState.update {
                            it.copy(sttDownloading = false, sttDownloaded = true, sttProgress = 1f)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "STT extract error: ${e.message}", e)
                        _uiState.update {
                            it.copy(sttDownloading = false, error = "STT extract: ${e.message}")
                        }
                    }
                }
                is DownloadState.Failed -> {
                    _uiState.update {
                        it.copy(sttDownloading = false, error = "Whisper: ${state.error}")
                    }
                }
                is DownloadState.Cancelled -> {
                    _uiState.update { it.copy(sttDownloading = false) }
                }
                else -> {}
            }
        }
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        Log.i(TAG, "Extracting ${archive.name} (${archive.length()} bytes) to ${destDir.absolutePath}")
        val fis = FileInputStream(archive)
        val bis = BufferedInputStream(fis)
        val bzis = BZip2CompressorInputStream(bis)
        val tais = TarArchiveInputStream(bzis)

        try {
            var entry = tais.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                    Log.d(TAG, "  DIR: ${entry.name}")
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out ->
                        tais.copyTo(out)
                    }
                    Log.i(TAG, "  FILE: ${entry.name} -> ${outFile.length()} bytes")
                }
                entry = tais.nextEntry
            }
        } finally {
            tais.close()
            bzis.close()
            bis.close()
            fis.close()
        }
        Log.i(TAG, "Extraction complete")
    }
}
