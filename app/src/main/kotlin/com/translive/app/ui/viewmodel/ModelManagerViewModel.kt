package com.translive.app.ui.viewmodel

import android.net.Uri
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.translive.app.R
import com.translive.app.data.LanguagePackRepository
import com.translive.app.data.ModelRepository
import com.translive.app.data.SettingsRepository
import com.translive.app.data.model.Language
import com.translive.app.data.model.LanguagePack
import com.translive.app.data.model.ModelCatalog
import com.translive.app.data.model.ModelFamily
import com.translive.app.data.model.ModelRuntime
import com.translive.app.data.model.ModelVariant
import com.translive.app.data.model.SttModelInfo
import com.translive.app.engine.DownloadState
import com.translive.app.engine.LiteRtTranslationEngine
import com.translive.app.engine.FastTranslateEngine
import com.translive.app.engine.ModelDownloadManager
import com.translive.app.engine.SpeechEngine
import com.translive.app.engine.TranslationEngine
import com.translive.app.i18n.LocalizedTextProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import com.translive.app.engine.PpOcrPackage
import javax.inject.Inject
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

enum class ModelStatus {
    NOT_DOWNLOADED,
    DOWNLOADING,
    PAUSED,
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
    val hasActiveVariant: Boolean = false,
    val activeDownloadCount: Int = 0,
    val pausedDownloadCount: Int = 0,
    val downloadProgress: Float? = null
)

data class ModelManagerUiState(
    val families: List<FamilyUiState> = emptyList(),
    val models: List<ModelItemState> = emptyList(),
    val externalModels: List<ModelItemState> = emptyList(),
    val travelPacks: List<LanguagePack> = emptyList(),
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
    val qwen3Downloaded: Boolean = false,
    val qwen3Downloading: Boolean = false,
    val qwen3Progress: Float = 0f,
    val selectedSpeechModel: String = SettingsRepository.SPEECH_MODEL_WHISPER_TINY,
    val cameraPack: CameraTranslationPackUiState? = null,
    val cameraLanguagePacks: List<CameraLanguagePackUiState> = emptyList(),
    val cameraLanguagePacksExpanded: Boolean = false,
    val cameraPackagePair: CameraPackagePairUiState? = null,
    val ocrPackageInstalled: Boolean = false,
    val ocrPackageBusy: Boolean = false,
    val error: String? = null,
    val successMessage: String? = null,
    val dictionaryEntriesCount: Int = 0,
    /** Variant pending license confirmation before download */
    val pendingLicenseVariant: ModelVariant? = null
)

/** ML Kit-owned offline language packages required by the current camera pair. */
data class CameraTranslationPackUiState(
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val isAutoSource: Boolean,
    val supported: Boolean,
    val isReady: Boolean,
    val isDownloading: Boolean = false,
    val requiredPackageCount: Int = 0,
    val downloadedPackageCount: Int = 0
)

/** One reusable ML Kit language model for fast camera translation. */
data class CameraLanguagePackUiState(
    val modelLanguageCode: String,
    val languages: List<Language>,
    val fastSupported: Boolean = true,
    val isDownloaded: Boolean,
    val isDownloading: Boolean = false
)

/** A user-picked fast camera pair in the collapsed Models group. */
data class CameraPackagePairUiState(
    val sourceLanguage: Language,
    val targetLanguage: Language,
    val fastSupported: Boolean = true,
    val isReady: Boolean,
    val isDownloading: Boolean = false,
    val requiredPackageCount: Int = 0,
    val downloadedPackageCount: Int = 0
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repo: ModelRepository,
    private val downloadManager: ModelDownloadManager,
    private val engine: TranslationEngine,
    private val liteRtEngine: LiteRtTranslationEngine,
    private val speechEngine: SpeechEngine,
    private val FastTranslateEngine: FastTranslateEngine,
    private val dictionaryRepository: com.translive.app.data.DictionaryRepository,
    private val languagePackRepository: LanguagePackRepository,
    private val settings: SettingsRepository,
    private val texts: LocalizedTextProvider
) : ViewModel() {

    companion object {
        private const val TAG = "ModelManagerVM"
    }

    private fun tr(id: Int, vararg args: Any): String =
        texts.text(id, *args)

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> tr(R.string.size_gb, bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> tr(R.string.size_mb, bytes / 1_048_576.0)
        bytes >= 1024L -> tr(R.string.size_kb, bytes / 1024.0)
        else -> tr(R.string.size_bytes, bytes)
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
        viewModelScope.launch {
            languagePackRepository.packs.collect { packs ->
                _uiState.update { it.copy(travelPacks = packs) }
            }
        }
        viewModelScope.launch {
            FastTranslateEngine.isDownloading.collect { downloading ->
                _uiState.update { state ->
                    state.copy(cameraPack = state.cameraPack?.copy(isDownloading = downloading))
                }
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
                    downloadState is DownloadState.Paused -> ModelStatus.PAUSED
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
                hasActiveVariant = variantStates.any { it.status == ModelStatus.ACTIVE },
                activeDownloadCount = variantStates.count { it.status == ModelStatus.DOWNLOADING },
                pausedDownloadCount = variantStates.count { it.status == ModelStatus.PAUSED },
                downloadProgress = variantStates.mapNotNull { item ->
                    when (val state = item.downloadState) {
                        is DownloadState.Downloading -> state.bytesDownloaded.toDouble() to state.totalBytes.toDouble()
                        is DownloadState.Paused -> state.bytesDownloaded.toDouble() to state.totalBytes.toDouble()
                        else -> null
                    }
                }.takeIf { it.isNotEmpty() }?.let { progressItems ->
                    val total = progressItems.sumOf { it.second }
                    if (total > 0.0) (progressItems.sumOf { it.first } / total).toFloat() else 0f
                }
            )
        }

        // Flat list for backward compat
        val allModels = families.flatMap { it.variants }
        val externalModels = repo.getExternalModels().map { variant ->
            ModelItemState(
                variant = variant,
                status = if (variant.id == activeId) ModelStatus.ACTIVE else ModelStatus.DOWNLOADED
            )
        }

        val sttVadState = downloads["stt-vad"]
        val sttWhisperState = downloads["stt-whisper"]
        val sttQwenState = downloads["stt-qwen3-asr-0.6b"]

        _uiState.update { old ->
            old.copy(
                families = families,
                models = allModels,
                externalModels = externalModels,
                totalDownloadedSize = repo.getTotalDownloadedSize(),
                availableSpace = repo.getAvailableSpace(),
                sttDownloaded = speechEngine.isVadDownloaded() && speechEngine.isWhisperDownloaded(),
                sttDownloading = sttVadState is DownloadState.Downloading ||
                        sttWhisperState is DownloadState.Downloading,
                sttProgress = when {
                    sttWhisperState is DownloadState.Downloading ->
                        0.05f + sttWhisperState.progress * 0.9f
                    sttVadState is DownloadState.Downloading ->
                        sttVadState.progress * 0.05f
                    else -> old.sttProgress
                },
                qwen3Downloaded = speechEngine.isVadDownloaded() && speechEngine.isQwen3Downloaded(),
                qwen3Downloading = sttQwenState is DownloadState.Downloading,
                qwen3Progress = when (sttQwenState) {
                    is DownloadState.Downloading -> sttQwenState.progress
                    is DownloadState.Paused -> sttQwenState.progress
                    else -> old.qwen3Progress
                },
                selectedSpeechModel = settings.speechModel
                ,ocrPackageInstalled = PpOcrPackage.validate(
                    File(appContext.filesDir, "ocr/${PpOcrPackage.ID}")
                ).valid
            )
        }
    }

    fun refreshModels() {
        updateDownloadStates(downloadManager.activeDownloads.value)
        refreshCameraTranslationPack()
        viewModelScope.launch(Dispatchers.IO) {
            dictionaryRepository.ensureSeeded()
            val count = dictionaryRepository.getTotalEntryCount()
            _uiState.update { it.copy(dictionaryEntriesCount = count) }
            languagePackRepository.refreshPackStatuses()
        }
    }

    fun downloadTravelPack(packId: String) {
        viewModelScope.launch {
            languagePackRepository.downloadLanguagePack(packId)
        }
    }

    fun deleteTravelPack(packId: String) {
        viewModelScope.launch {
            languagePackRepository.removeLanguagePack(packId)
        }
    }

    private fun refreshCameraTranslationPack() {
        val source = settings.cameraSourceLanguage
        val target = settings.cameraTargetLanguage
        val isAutoSource = settings.cameraSourceAuto
        viewModelScope.launch(Dispatchers.IO) {
            val status = FastTranslateEngine.getPackageStatus(source.code, target.code)
            val downloadedCodes = FastTranslateEngine.downloadedLanguageCodes()
            val languagePacks = FastTranslateEngine.availableLanguagePackages().map { pack ->
                CameraLanguagePackUiState(
                    modelLanguageCode = pack.modelLanguageCode,
                    languages = pack.languages,
                    fastSupported = pack.fastSupported,
                    isDownloaded = pack.modelLanguageCode in downloadedCodes,
                    isDownloading = FastTranslateEngine.isDownloading.value
                )
            }
            val existingPair = _uiState.value.cameraPackagePair
            val pairSource = existingPair?.sourceLanguage ?: source
            val pairTarget = existingPair?.targetLanguage ?: target
            val pairStatus = FastTranslateEngine.getPackageStatus(pairSource.code, pairTarget.code)
            _uiState.update {
                it.copy(
                    cameraPack = CameraTranslationPackUiState(
                        sourceLanguage = source,
                        targetLanguage = target,
                        isAutoSource = isAutoSource,
                        supported = status.supported,
                        isReady = status.isReady,
                        isDownloading = FastTranslateEngine.isDownloading.value,
                        requiredPackageCount = status.requiredLanguageCodes.size,
                        downloadedPackageCount = status.downloadedLanguageCodes.count {
                            it in status.requiredLanguageCodes
                        }
                    ),
                    cameraLanguagePacks = languagePacks,
                    cameraPackagePair = CameraPackagePairUiState(
                        sourceLanguage = pairSource,
                        targetLanguage = pairTarget,
                        fastSupported = pairStatus.supported,
                        isReady = pairStatus.isReady,
                        isDownloading = existingPair?.isDownloading == true || FastTranslateEngine.isDownloading.value,
                        requiredPackageCount = pairStatus.requiredLanguageCodes.size,
                        downloadedPackageCount = pairStatus.downloadedLanguageCodes.count {
                            it in pairStatus.requiredLanguageCodes
                        }
                    )
                )
            }
        }
    }

    fun downloadCameraTranslationPack() {
        val pack = _uiState.value.cameraPack ?: return
        if (!pack.supported || pack.isReady || pack.isDownloading) return

        _uiState.update { state ->
            state.copy(cameraPack = pack.copy(isDownloading = true))
        }
        viewModelScope.launch(Dispatchers.IO) {
            val activated = FastTranslateEngine.downloadAndActivate(
                pack.sourceLanguage.code,
                pack.targetLanguage.code
            )
            if (!activated) {
                _uiState.update {
                    it.copy(error = tr(R.string.camera_pack_download_failed))
                }
            }
            refreshCameraTranslationPack()
        }
    }

    fun toggleCameraLanguagePacks() {
        _uiState.update { it.copy(cameraLanguagePacksExpanded = !it.cameraLanguagePacksExpanded) }
    }

    fun selectCameraPackagePairSource(language: Language) {
        val target = _uiState.value.cameraPackagePair?.targetLanguage
            ?: settings.cameraTargetLanguage
        _uiState.update {
            it.copy(
                cameraPackagePair = CameraPackagePairUiState(
                    sourceLanguage = language,
                    targetLanguage = if (target == language) Language.ENGLISH else target,
                    fastSupported = false,
                    isReady = false
                )
            )
        }
        refreshCameraTranslationPack()
    }

    fun selectCameraPackagePairTarget(language: Language) {
        val source = _uiState.value.cameraPackagePair?.sourceLanguage
            ?: if (settings.cameraSourceAuto) Language.RUSSIAN else settings.cameraSourceLanguage
        _uiState.update {
            it.copy(
                cameraPackagePair = CameraPackagePairUiState(
                    sourceLanguage = if (source == language) Language.ENGLISH else source,
                    targetLanguage = language,
                    fastSupported = false,
                    isReady = false
                )
            )
        }
        refreshCameraTranslationPack()
    }

    fun downloadCameraPackagePair() {
        val pair = _uiState.value.cameraPackagePair ?: return
        if (pair.isReady || pair.isDownloading) return
        _uiState.update { it.copy(cameraPackagePair = pair.copy(isDownloading = true)) }
        viewModelScope.launch(Dispatchers.IO) {
            val downloaded = FastTranslateEngine.downloadPairPackages(
                pair.sourceLanguage.code,
                pair.targetLanguage.code
            )
            if (!downloaded) {
                _uiState.update { it.copy(error = tr(R.string.camera_pack_download_failed)) }
            }
            refreshCameraTranslationPack()
        }
    }

    fun downloadCameraLanguagePack(modelLanguageCode: String) {
        val pack = _uiState.value.cameraLanguagePacks.firstOrNull {
            it.modelLanguageCode == modelLanguageCode
        } ?: return
        if (pack.isDownloaded || pack.isDownloading) return

        _uiState.update { state ->
            state.copy(
                cameraLanguagePacks = state.cameraLanguagePacks.map {
                    if (it.modelLanguageCode == modelLanguageCode) it.copy(isDownloading = true) else it
                }
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val downloaded = FastTranslateEngine.downloadLanguagePackages(listOf(modelLanguageCode))
            if (!downloaded) {
                _uiState.update { it.copy(error = tr(R.string.camera_pack_download_failed)) }
            }
            refreshCameraTranslationPack()
        }
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
            _uiState.update { it.copy(error = tr(R.string.error_insufficient_space, formatSize(variant.sizeBytes))) }
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
                    _uiState.update { it.copy(error = tr(R.string.error_prefix, state.error)) }
                }
                else -> {}
            }
        }
    }

    fun cancelDownload(variant: ModelVariant) {
        downloadManager.cancelDownload(variant.id)
    }

    fun pauseDownload(variant: ModelVariant) {
        downloadManager.pauseDownload(variant.id)
    }

    fun resumeDownload(variant: ModelVariant) {
        downloadModel(variant)
    }

    fun selectModel(variant: ModelVariant) {
        if (!repo.isDownloaded(variant)) return

        _uiState.update { it.copy(isLoadingModel = true, error = null) }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                engine.unloadModel()
                liteRtEngine.unloadModel()
                val path = repo.getModelPath(variant) ?: return@launch

                if (variant.supportsGpu && !variant.supportsCpu) {
                    settings.backend = SettingsRepository.BACKEND_GPU
                }
                val threads = settings.threads
                val loaded = if (variant.runtime == ModelRuntime.LITERT_LM) {
                    liteRtEngine.loadModel(path, settings.backend, threads)
                } else {
                    engine.loadModel(path, threads, settings.backend)
                }

                if (!loaded) {
                    _uiState.update { it.copy(error = tr(R.string.error_load_named_model, variant.quantName)) }
                } else {
                    // A model becomes active only after its runtime has loaded it.
                    // This keeps an incompatible external file from replacing a
                    // working translator configuration.
                    repo.setActiveModelId(variant.id)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = tr(R.string.error_prefix, e.message ?: "")) }
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

    fun selectSpeechModel(model: String) {
        val isReady = when (model) {
            SettingsRepository.SPEECH_MODEL_QWEN3_ASR_06B -> speechEngine.isVadDownloaded() && speechEngine.isQwen3Downloaded()
            else -> speechEngine.isVadDownloaded() && speechEngine.isWhisperDownloaded()
        }
        if (!isReady) return
        speechEngine.release()
        settings.speechModel = model
        refreshModels()
    }

    fun deleteQwen3Model() {
        if (settings.speechModel == SettingsRepository.SPEECH_MODEL_QWEN3_ASR_06B) {
            settings.speechModel = SettingsRepository.SPEECH_MODEL_WHISPER_TINY
        }
        speechEngine.release()
        speechEngine.qwen3Dir.deleteRecursively()
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
                            successMessage = tr(R.string.success_model_exported, variant.quantName)
                        )
                    }
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isExporting = false,
                            error = tr(R.string.error_export_prefix, error.message ?: "")
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
                onSuccess = { imported ->
                    // A catalog model may become the first active model only
                    // through the same load-before-activate gate. External
                    // models always remain explicit user choices.
                    if (repo.getActiveModelId() == null && imported.recognizedCatalogVariant) {
                        selectModel(imported.variant)
                    }
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importProgress = 1f,
                            successMessage = when {
                                imported.alreadyPresent -> "Модель уже добавлена и прошла проверку"
                                imported.integrityVerified -> "Проверенная модель из каталога добавлена"
                                imported.recognizedCatalogVariant -> "Модель из каталога добавлена; контрольная сумма для неё пока не опубликована"
                                else -> "Внешняя модель добавлена в отдельный список"
                            }
                        )
                    }
                    refreshModels()
                },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            error = error.message ?: tr(R.string.error_import)
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
                    // VAD done, now download Whisper.
                    downloadWhisper(sttDir)
                }
                is DownloadState.Failed -> {
                    _uiState.update {
                        it.copy(sttDownloading = false, error = tr(R.string.error_prefix, "VAD: ${vadState.error}"))
                    }
                }
                is DownloadState.Cancelled -> {
                    _uiState.update { it.copy(sttDownloading = false) }
                }
                else -> {}
            }
        }
    }

    /** Import one complete PP-OCR MNN zip; incomplete archives are discarded. */
    fun importOcrPackageFromUri(uri: Uri) {
        if (_uiState.value.ocrPackageBusy) return
        _uiState.update { it.copy(ocrPackageBusy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val root = File(appContext.filesDir, "ocr/${PpOcrPackage.ID}")
            val staging = File(appContext.cacheDir, "ocr-import-${System.nanoTime()}")
            val result = runCatching {
                staging.deleteRecursively()
                staging.mkdirs()
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    ZipInputStream(BufferedInputStream(input)).use { zip ->
                        generateSequence { zip.nextEntry }.forEach { entry ->
                            if (!entry.isDirectory) {
                                val output = File(staging, File(entry.name).name)
                                output.outputStream().use { out -> zip.copyTo(out) }
                            }
                            zip.closeEntry()
                        }
                    }
                } ?: error("Не удалось открыть OCR-архив")
                val validation = PpOcrPackage.validate(staging)
                check(validation.valid) { validation.message }
                root.deleteRecursively()
                staging.renameTo(root)
                "PP-OCRv6 MNN-пакет установлен и проверен"
            }
            staging.deleteRecursively()
            result.fold(
                onSuccess = { message -> _uiState.update { it.copy(ocrPackageBusy = false, ocrPackageInstalled = true, successMessage = message) } },
                onFailure = { error -> _uiState.update { it.copy(ocrPackageBusy = false, error = "OCR: ${error.message ?: "архив не прошёл проверку"}") } }
            )
        }
    }

    /** Download Qwen3-ASR only after user explicitly asks for the quality STT mode. */
    fun downloadQwen3Model() {
        if (_uiState.value.qwen3Downloading || speechEngine.isQwen3Downloaded()) return
        val sttDir = File(speechEngine.vadFile.parent ?: return).apply { mkdirs() }
        _uiState.update { it.copy(qwen3Downloading = true, qwen3Progress = 0f) }
        val startQwen = { downloadQwen3Archive(sttDir) }
        if (speechEngine.isVadDownloaded()) {
            startQwen()
            return
        }
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
        downloadManager.startDownload(vadVariant, speechEngine.vadFile) { state ->
            when (state) {
                is DownloadState.Completed -> startQwen()
                is DownloadState.Failed -> _uiState.update { it.copy(qwen3Downloading = false, error = tr(R.string.error_prefix, "VAD: ${state.error}")) }
                is DownloadState.Cancelled -> _uiState.update { it.copy(qwen3Downloading = false) }
                else -> Unit
            }
        }
    }

    private fun downloadQwen3Archive(sttDir: File) {
        val archive = File(sttDir, SttModelInfo.QWEN3_ARCHIVE)
        val variant = ModelVariant(
            id = "stt-qwen3-asr-0.6b",
            quantName = SttModelInfo.QWEN3_DISPLAY_NAME,
            displayName = SttModelInfo.QWEN3_DISPLAY_NAME,
            description = "Offline quality ASR, CPU",
            sizeBytes = SttModelInfo.QWEN3_ARCHIVE_SIZE_BYTES,
            ramEstimateMb = SttModelInfo.QWEN3_RAM_MB,
            downloadUrl = SttModelInfo.QWEN3_DOWNLOAD_URL,
            filename = SttModelInfo.QWEN3_ARCHIVE,
            sha256 = SttModelInfo.QWEN3_SHA256
        )
        downloadManager.startDownload(variant, archive) { state ->
            when (state) {
                is DownloadState.Completed -> try {
                    _uiState.update { it.copy(qwen3Progress = 0.98f) }
                    withContext(Dispatchers.IO) {
                        extractTarBz2(archive, sttDir)
                        archive.delete()
                    }
                    if (!speechEngine.isQwen3Downloaded()) {
                        throw IllegalStateException("Qwen3-ASR archive has missing or incomplete files")
                    }
                    _uiState.update { it.copy(qwen3Downloading = false, qwen3Downloaded = true, qwen3Progress = 1f) }
                } catch (e: Exception) {
                    Log.e(TAG, "Qwen3-ASR extract error", e)
                    _uiState.update { it.copy(qwen3Downloading = false, error = tr(R.string.error_prefix, "Qwen3-ASR: ${e.message ?: ""}")) }
                }
                is DownloadState.Failed -> _uiState.update { it.copy(qwen3Downloading = false, error = tr(R.string.error_prefix, "Qwen3-ASR: ${state.error}")) }
                is DownloadState.Cancelled -> _uiState.update { it.copy(qwen3Downloading = false) }
                else -> Unit
            }
        }
    }

    private fun downloadWhisper(sttDir: File) {
        val whisperArchive = File(sttDir, SttModelInfo.WHISPER_ARCHIVE)
        val whisperVariant = ModelVariant(
            id = "stt-whisper",
            quantName = SttModelInfo.WHISPER_DISPLAY_NAME,
            displayName = SttModelInfo.WHISPER_DISPLAY_NAME,
            description = SttModelInfo.WHISPER_DISPLAY_NAME,
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
                            it.copy(sttDownloading = false, error = tr(R.string.error_prefix, "STT extract: ${e.message ?: ""}"))
                        }
                    }
                }
                is DownloadState.Failed -> {
                    _uiState.update {
                        it.copy(sttDownloading = false, error = tr(R.string.error_prefix, "Whisper: ${state.error}"))
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
                val root = destDir.canonicalFile
                val candidate = outFile.canonicalFile
                require(candidate.path.startsWith(root.path + File.separator)) {
                    "Unsafe archive entry: ${entry.name}"
                }
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

